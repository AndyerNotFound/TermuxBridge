// HAND-WRITTEN equivalent of the AIDL-generated interface for
// lll.bridge.termux.IPermsHelper (Termux cannot execute the SDK aidl binary).
// Keep in sync with app/src/main/aidl/lll/bridge/termux/IPermsHelper.aidl.
package lll.bridge.termux;

public interface IPermsHelper extends android.os.IInterface {
    /** exec cmd, return "exit=<n>\n<stdout><stderr>" */
    String run(String[] cmd) throws android.os.RemoteException;

    /** Binder stub, generated-style. */
    public static abstract class Stub extends android.os.Binder implements IPermsHelper {
        private static final String DESCRIPTOR = "lll.bridge.termux.IPermsHelper";
        static final int TRANSACTION_run = android.os.IBinder.FIRST_CALL_TRANSACTION;

        public static IPermsHelper asInterface(android.os.IBinder obj) {
            if (obj == null) return null;
            android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin != null && (iin instanceof IPermsHelper))
                return (IPermsHelper) iin;
            return new Proxy(obj);
        }

        public Stub() { this.attachInterface(this, DESCRIPTOR); }

        @Override public android.os.IBinder asBinder() { return this; }

        @Override protected boolean onTransact(int code, android.os.Parcel data,
                android.os.Parcel reply, int flags) throws android.os.RemoteException {
            switch (code) {
                case TRANSACTION_run: {
                    data.enforceInterface(DESCRIPTOR);
                    String[] _arg0 = data.createStringArray();
                    String _result = this.run(_arg0);
                    reply.writeNoException();
                    reply.writeString(_result);
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static class Proxy implements IPermsHelper {
            private android.os.IBinder mRemote;
            Proxy(android.os.IBinder remote) { mRemote = remote; }
            @Override public android.os.IBinder asBinder() { return mRemote; }

            @Override public String run(String[] cmd) throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeStringArray(cmd);
                    boolean _status = mRemote.transact(TRANSACTION_run, _data, _reply, 0);
                    _reply.readException();
                    return _reply.readString();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }
        }
    }
}
