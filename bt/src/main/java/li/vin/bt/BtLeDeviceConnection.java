package li.vin.bt;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.support.annotation.NonNull;
import android.util.Log;

import java.nio.charset.Charset;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Func1;
import rx.observables.ConnectableObservable;
import rx.subjects.PublishSubject;
import rx.subscriptions.Subscriptions;

/*package*/ class BtLeDeviceConnection extends BluetoothGattCallback implements DeviceConnection {
  private static final String TAG = BtLeDeviceConnection.class.getSimpleName();

  private final PublishSubject<ConnectionStateChangeMsg> connectionStateObservable = PublishSubject.create();
  private final PublishSubject<ServiceMsg> serviceObservable = PublishSubject.create();
  private final PublishSubject<BluetoothGattCharacteristic> characteristicReadObservable = PublishSubject.create();
  private final PublishSubject<CharacteristicChangeMsg> characteristicChangedObservable = PublishSubject.create();
  private final PublishSubject<CharacteristicWriteMsg> characteristicWriteObservable = PublishSubject.create();
  private final PublishSubject<DescriptorWriteMsg> descriptorWriteObservable = PublishSubject.create();

  private final PublishSubject<ConnectableObservable<?>> writeQueue = PublishSubject.create();

  private final Map<UUID, Observable<?>> mUuidObservables = new ConcurrentHashMap<>();
  private final Map<Param<?>, Observable<?>> mParamObservables = new IdentityHashMap<>();

  private final Context mContext;
  private final BluetoothDevice mDevice;
  private final String mUnlockKey;

  public BtLeDeviceConnection(@NonNull Context context, @NonNull BluetoothDevice device, @NonNull String unlockKey) {
    mContext = context;
    mDevice = device;
    mUnlockKey = unlockKey;
  }

  @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
    Log.i(TAG, String.format("device(%s) onConnectionStateChange status(%s) newState(%s)",
      gatt.getDevice(), Utils.gattStatus(status), Utils.btState(newState)));

    this.connectionStateObservable.onNext(new ConnectionStateChangeMsg(gatt, status, newState));
  }

  @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
    Log.i(TAG, String.format("device(%s) onServicesDiscovered status(%s)", gatt.getDevice(),
      Utils.gattStatus(status)));

    this.serviceObservable.onNext(new ServiceMsg(gatt, status));
  }

  @Override public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
    Log.i(TAG, String.format("device(%s) onCharacteristicRead characteristic(%s) status(%s)",
      gatt.getDevice(), characteristic.getUuid(), Utils.gattStatus(status)));

    this.characteristicReadObservable.onNext(characteristic);
//    this.characteristicChangedObservable.onNext(new CharacteristicChangeMsg(gatt, characteristic));
  }

  @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
//    Log.i(TAG, String.format("device(%s) onCharacteristicChanged characteristic(%s)",
//      gatt.getDevice(), characteristic.getUuid()));
    this.characteristicChangedObservable.onNext(new CharacteristicChangeMsg(gatt, characteristic));
  }

  @Override public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
    Log.i(TAG, String.format("device(%s) onCharacteristicWrite characteristic(%s) status(%s)",
      gatt.getDevice(), characteristic, Utils.gattStatus(status)));

    this.characteristicWriteObservable.onNext(new CharacteristicWriteMsg(gatt, characteristic, status));
  }

  @Override public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
    Log.i(TAG, String.format("device(%s) onDescriptorWrite descriptor(%s) status(%s)",
      gatt.getDevice(), descriptor.getCharacteristic().getUuid(), Utils.gattStatus(status)));

    this.descriptorWriteObservable.onNext(new DescriptorWriteMsg(gatt, descriptor, status));
  }

  @Override public Observable<Void> resetDtcs() {
    Log.d(TAG, "resetDtcs");
    final ConnectableObservable<Void> clearDtcsObservable = connectionObservable
      .flatMap(new Func1<GattService, Observable<CharacteristicWriteMsg>>() {
        @Override
        public Observable<CharacteristicWriteMsg> call(final GattService gs) {
          Log.d(TAG, "resetDtcs: connected");
          final BluetoothGattCharacteristic characteristic = gs.service.getCharacteristic(Uuids.CLEAR_DTCS);
          if (characteristic == null) {
            throw new RuntimeException("bluetooth service is missing the CLEAR_DTCS characteristic");
          }

          characteristic.setValue(new byte[] {0x00});

          if (!gs.gatt.writeCharacteristic(characteristic)) {
            throw new RuntimeException("failed to initiate write to clear DTCs");
          }

          Log.d(TAG, "resetDtcs: waiting for write confirmation");
          return characteristicWriteObservable;
        }
      })
      .filter(new Func1<CharacteristicWriteMsg, Boolean>() {
        @Override
        public Boolean call(CharacteristicWriteMsg msg) {
          return Uuids.CLEAR_DTCS.equals(msg.characteristic.getUuid());
        }
      })
      .first()
      .map(new Func1<CharacteristicWriteMsg, Void>() {
        @Override public Void call(CharacteristicWriteMsg msg) {
          if (BluetoothGatt.GATT_SUCCESS != msg.status) {
            throw new RuntimeException("failed to clear the DTCs");
          }
          Log.d(TAG, "resetDtcs: dtcs reset");
          return null;
        }
      })
      .replay(1);

    writeQueue.onNext(clearDtcsObservable);

    return clearDtcsObservable.asObservable();
  }

  @Override public <T> Observable<T> observe(@NonNull final Param<T> param) {
    if (!(param instanceof ParamImpl)) {
      throw new AssertionError("all Params must be instances of ParamImpl");
    }

    return observe((ParamImpl<T, ?>) param);
  }

  private <T, P> Observable<T> observe(@NonNull final ParamImpl<T, P> param) {
    @SuppressWarnings("unchecked")
    Observable<T> paramObservable = (Observable<T>) mParamObservables.get(param);
    if (paramObservable == null) {
      @SuppressWarnings("unchecked")
      Observable<P> uuidObservable = (Observable<P>) mUuidObservables.get(param.uuid);
      if (uuidObservable == null) {
        final Func1<BluetoothGattCharacteristic, Boolean> matchesUuid = new Func1<BluetoothGattCharacteristic, Boolean>() {
          @Override public Boolean call(BluetoothGattCharacteristic characteristic) {
            return param.uuid.equals(characteristic.getUuid());
          }
        };

        final Func1<BluetoothGattCharacteristic, P> parseCharacteristic = new Func1<BluetoothGattCharacteristic, P>() {
          @Override public P call(BluetoothGattCharacteristic characteristic) {
            return param.parseCharacteristic(characteristic);
          }
        };

        uuidObservable = connectionObservable
          .flatMap(new Func1<GattService, Observable<BluetoothGattCharacteristic>>() {
            @Override public Observable<BluetoothGattCharacteristic> call(final GattService gs) {
              final BluetoothGattCharacteristic characteristic = gs.service.getCharacteristic(param.uuid);
              if (characteristic == null) {
                throw new RuntimeException("no such characteristic: " + param.uuid);
              }

              final ConnectableObservable<BluetoothGattCharacteristic> readObservable = param.shouldRead
                ? makeReadObservable(param, gs.gatt, characteristic)
                : null;

              final ConnectableObservable<DescriptorWriteMsg> notiObservable = param.hasNotifications
                ? makeNotiObservable(param, gs.gatt, characteristic)
                : null;

              if (readObservable == null && notiObservable == null) {
                return Observable.empty();
              }

              if (readObservable != null && notiObservable == null) {
                writeQueue.onNext(readObservable);
                return readObservable;
              }

              final Observable<BluetoothGattCharacteristic> notiValObservable = notiObservable
                .flatMap(getCharacteristicChanges)
                .map(pluckCharacteristic);

              if (readObservable == null) {
                writeQueue.onNext(notiObservable);
                return notiValObservable;
              } else {
                writeQueue.onNext(readObservable);
                writeQueue.onNext(notiObservable);
                return Observable.merge(readObservable, notiValObservable);
              }
            }
          })
          .filter(matchesUuid)
          .map(parseCharacteristic)
          .doOnUnsubscribe(new Action0() {
            @Override
            public void call() {
              Log.d("uuid.doOnUnsubscribe", "unsubscribing " + param.uuid);
              mUuidObservables.remove(param.uuid);
            }
          })
          .share();

        mUuidObservables.put(param.uuid, uuidObservable);
      }

      paramObservable = uuidObservable
        .filter(new Func1<P, Boolean>() {
          @Override public Boolean call(P s) {
            return param.matches(s);
          }
        })
        .map(new Func1<P, T>() {
          @Override public T call(P s) {
            return param.parseVal(s);
          }
        })
        .distinctUntilChanged()
        .doOnUnsubscribe(new Action0() {
          @Override
          public void call() {
            Log.d("param.doOnUnsubscribe", "unsubscribing " + param.uuid);
            mParamObservables.remove(param);
          }
        })
        .share();

      mParamObservables.put(param, paramObservable);
    }

    return paramObservable;
  }

  private final Func1<Object, Observable<CharacteristicChangeMsg>> getCharacteristicChanges = new Func1<Object, Observable<CharacteristicChangeMsg>>() {
    @Override public Observable<CharacteristicChangeMsg> call(Object o) {
      return characteristicChangedObservable;
    }
  };

  private static final Func1<CharacteristicChangeMsg, BluetoothGattCharacteristic> pluckCharacteristic = new Func1<CharacteristicChangeMsg, BluetoothGattCharacteristic>() {
    @Override public BluetoothGattCharacteristic call(CharacteristicChangeMsg characteristicChangeMsg) {
      return characteristicChangeMsg.characteristic;
    }
  };

  private final Observable<GattService> connectionObservable = connectionStateObservable
  .filter(new Func1<ConnectionStateChangeMsg, Boolean>() {
    @Override
    public Boolean call(ConnectionStateChangeMsg msg) {
      return BluetoothProfile.STATE_CONNECTED == msg.newState;
    }
  })
  .first()
  .flatMap(new Func1<ConnectionStateChangeMsg, Observable<ServiceMsg>>() {
    @Override public Observable<ServiceMsg> call(ConnectionStateChangeMsg msg) {
      Log.d(TAG, "connected to device. Discovering services...");
      msg.gatt.discoverServices();
      return serviceObservable;
    }
  })
  .flatMap(new Func1<ServiceMsg, Observable<GattService>>() {
    @Override
    public Observable<GattService> call(ServiceMsg msg) {
      if (BluetoothGatt.GATT_SUCCESS != msg.status) {
        throw new RuntimeException("failed to find services"); // TODO: better error
      }

      final BluetoothGattService service = msg.gatt.getService(Uuids.SERVICE);
      if (service == null) {
        throw new RuntimeException("service not found: " + Uuids.SERVICE); // TODO: better error
      }

      Log.d(TAG, "found Vinli service. Unlocking device...");

      final BluetoothGattCharacteristic characteristic = service.getCharacteristic(Uuids.UNLOCK);
      if (characteristic == null) {
        throw new RuntimeException("no such characteristic: " + Uuids.UNLOCK);
      }

      characteristic.setValue(mUnlockKey.getBytes(Charset.forName("ASCII")));
      if (!msg.gatt.writeCharacteristic(characteristic)) {
        throw new RuntimeException("failed to start write to unlock device");
      }

      return characteristicWriteObservable
        .filter(new Func1<CharacteristicWriteMsg, Boolean>() {
          @Override
          public Boolean call(CharacteristicWriteMsg msg) {
            return Uuids.UNLOCK.equals(msg.characteristic.getUuid());
          }
        })
        .first()
        .map(new Func1<CharacteristicWriteMsg, GattService>() {
          @Override
          public GattService call(CharacteristicWriteMsg msg) {
            if (BluetoothGatt.GATT_SUCCESS != msg.status) {
              throw new RuntimeException("failed to unlock service: " + Utils.gattStatus(msg.status));
            }

            Log.d(TAG, "device unlocked");

            return new GattService(msg.gatt, service);
          }
        });
    }
  })
  .takeUntil(connectionStateObservable.filter(new Func1<ConnectionStateChangeMsg, Boolean>() {
    @Override
    public Boolean call(ConnectionStateChangeMsg msg) {
      return BluetoothProfile.STATE_DISCONNECTED == msg.newState;
    }
  }))
  .lift(new Observable.Operator<GattService, GattService>() {
    @Override
    public Subscriber<? super GattService> call(Subscriber<? super GattService> subscriber) {
      Log.d(TAG, "starting device connection");
      final BluetoothGatt gatt = mDevice.connectGatt(mContext, false, BtLeDeviceConnection.this);
      final Subscription writeQueueSubscription = writeQueue
        .onBackpressureBuffer()
        .subscribe(new WriteQueueConsumer());

      subscriber.add(Subscriptions.create(new Action0() {
        @Override
        public void call() {
          Log.d("GattConnection", "disconnecting from gatt after all unsubscribed");
          writeQueueSubscription.unsubscribe();
//          gatt.disconnect();
          gatt.close();
        }
      }));

      return subscriber;
    }
  })
  .replay(1)
  .refCount();

  private static final class WriteQueueConsumer extends Subscriber<ConnectableObservable<?>> {
    @Override public void onCompleted() { }
    @Override public void onError(Throwable e) { }

    @Override public void onStart() {
      request(1);
    }

    @Override public void onNext(ConnectableObservable<?> connectableObservable) {
      Log.i("WriteQueue", "consuming next item");

      connectableObservable.subscribe(new Subscriber<Object>() {
        @Override public void onNext(Object whatevs) { }

        @Override public void onError(Throwable e) {
          Log.e(TAG, "WriteQueue item failed", e);
          Log.d("requestNext", "requesting next writeQueue item");
          WriteQueueConsumer.this.request(1);
        }

        @Override public void onCompleted() {
          Log.d("requestNext", "requesting next writeQueue item");
          WriteQueueConsumer.this.request(1);
        }
      });

      connectableObservable.connect();
    }
  }

  private ConnectableObservable<BluetoothGattCharacteristic> makeReadObservable(final ParamImpl<?, ?> param,
      final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic) {
    return characteristicReadObservable
      .filter(new Func1<BluetoothGattCharacteristic, Boolean>() {
        @Override public Boolean call(BluetoothGattCharacteristic chara) {
          Log.i("makeReadObservable", "comparing " + param.uuid + " to " + chara.getUuid());
          return param.uuid.equals(chara.getUuid());
        }
      })
      .first()
      .doOnSubscribe(new Action0() {
        @Override public void call() {
          if (!gatt.readCharacteristic(characteristic)) {
            throw new RuntimeException("failed to initiate read of characteristic " + param.uuid);
          }
        }
      })
      .publish();
  }

  private ConnectableObservable<DescriptorWriteMsg> makeNotiObservable(final ParamImpl<?, ?> param,
      final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic) {
    return descriptorWriteObservable
      .filter(new Func1<DescriptorWriteMsg, Boolean>() {
        @Override public Boolean call(DescriptorWriteMsg msg) {
          return param.uuid.equals(msg.descriptor.getCharacteristic().getUuid());
        }
      })
      .first()
      .lift(new Observable.Operator<DescriptorWriteMsg, DescriptorWriteMsg>() {
        @Override public Subscriber<? super DescriptorWriteMsg> call(Subscriber<? super DescriptorWriteMsg> subscriber) {
          if (!gatt.setCharacteristicNotification(characteristic, true)) {
            throw new RuntimeException("failed to initiate streaming for characteristic " + param.uuid);
          }

          final BluetoothGattDescriptor descriptor =
            characteristic.getDescriptor(Uuids.CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID);

          // different Bluetooth Profile implementations support updates via either notification or indication.
          // detect which this device supports and use it.
          final int propNoti = BluetoothGattCharacteristic.PROPERTY_NOTIFY;

          final byte[] enableNotificationsValue = (characteristic.getProperties() & propNoti) == propNoti
            ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;

          descriptor.setValue(enableNotificationsValue);

          if (!gatt.writeDescriptor(descriptor)) {
            throw new RuntimeException("failed to initiate streaming for characteristic " + param.uuid);
          }

          subscriber.add(Subscriptions.create(new Action0() {
            @Override public void call() {
              Log.d("GattNotificationsOff", characteristic.getUuid().toString());
              gatt.setCharacteristicNotification(characteristic, false);

              Log.d("ChangeSubscriber", "queuing notification stop for " + param.uuid);
              writeQueue.onNext(makeStopNotiObservable(param, gatt, descriptor));
            }
          }));

          return subscriber;
        }
      })
      .publish();
  }

  private ConnectableObservable<? extends Object> makeStopNotiObservable(final ParamImpl<?, ?> param,
      final BluetoothGatt gatt, final BluetoothGattDescriptor descriptor) {
    return descriptorWriteObservable
      .filter(new Func1<DescriptorWriteMsg, Boolean>() {
        @Override public Boolean call(DescriptorWriteMsg msg) {
          return param.uuid.equals(msg.descriptor.getCharacteristic().getUuid());
        }
      })
      .first()
      .doOnSubscribe(new Action0() {
        @Override public void call() {
          descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
          if (!gatt.writeDescriptor(descriptor)) {
            throw new RuntimeException("failed to initiate stopping of notifications for " + param.uuid);
          }
        }
      })
      .publish();
  }

  private static final class ConnectionStateChangeMsg {
    // CHECKSTYLE.OFF: VisibilityModifier
    public final BluetoothGatt gatt;
    // CHECKSTYLE.ON
    public final int status;
    public final int newState;

    public ConnectionStateChangeMsg(BluetoothGatt gatt, int status, int newState) {
      this.gatt = gatt;
      this.status = status;
      this.newState = newState;
    }
  }

  private static final class ServiceMsg {
    // CHECKSTYLE.OFF: VisibilityModifier
    public final BluetoothGatt gatt;
    // CHECKSTYLE.ON
    public final int status;

    public ServiceMsg(BluetoothGatt gatt, int status) {
      this.gatt = gatt;
      this.status = status;
    }
  }

  private static final class CharacteristicChangeMsg {
    // CHECKSTYLE.OFF: VisibilityModifier
    public final BluetoothGatt gatt;
    public final BluetoothGattCharacteristic characteristic;
    // CHECKSTYLE.ON

    public CharacteristicChangeMsg(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
      this.gatt = gatt;
      this.characteristic = characteristic;
    }
  }

  private static final class CharacteristicWriteMsg {
    // CHECKSTYLE.OFF: VisibilityModifier
    public final BluetoothGatt gatt;
    public final BluetoothGattCharacteristic characteristic;
    // CHECKSTYLE.ON
    public final int status;

    public CharacteristicWriteMsg(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
      this.gatt = gatt;
      this.characteristic = characteristic;
      this.status = status;
    }
  }

  private static final class DescriptorWriteMsg {
    // CHECKSTYLE.OFF: VisibilityModifier
    public final BluetoothGatt gatt;
    public final BluetoothGattDescriptor descriptor;
    // CHECKSTYLE.ON
    public final int status;

    public DescriptorWriteMsg(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
      this.gatt = gatt;
      this.descriptor = descriptor;
      this.status = status;
    }
  }

  private static final class GattService {
    // CHECKSTYLE.OFF: VisibilityModifier
    public final BluetoothGatt gatt;
    public final BluetoothGattService service;
    // CHECKSTYLE.ON

    public GattService(BluetoothGatt gatt, BluetoothGattService service) {
      this.gatt = gatt;
      this.service = service;
    }
  }

}
