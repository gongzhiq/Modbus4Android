package com.licheedev.modbus4android;

import android.annotation.SuppressLint;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.msg.ReadCoilsRequest;
import com.serotonin.modbus4j.msg.ReadCoilsResponse;
import com.serotonin.modbus4j.msg.ReadDiscreteInputsRequest;
import com.serotonin.modbus4j.msg.ReadDiscreteInputsResponse;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersRequest;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;
import com.serotonin.modbus4j.msg.ReadInputRegistersRequest;
import com.serotonin.modbus4j.msg.ReadInputRegistersResponse;
import com.serotonin.modbus4j.msg.WriteCoilRequest;
import com.serotonin.modbus4j.msg.WriteCoilResponse;
import com.serotonin.modbus4j.msg.WriteCoilsRequest;
import com.serotonin.modbus4j.msg.WriteCoilsResponse;
import com.serotonin.modbus4j.msg.WriteRegisterRequest;
import com.serotonin.modbus4j.msg.WriteRegisterResponse;
import com.serotonin.modbus4j.msg.WriteRegistersRequest;
import com.serotonin.modbus4j.msg.WriteRegistersResponse;
import com.serotonin.modbus4j.msg.CustomWriteRegistersRequest;
import com.serotonin.modbus4j.msg.CustomWriteRegistersResponse;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * ModbusWorker实现，实现了初始化modbus，并增加了线圈、离散量输入、寄存器的读写方法
 */
public class ModbusWorker implements IModbusWorker {

    private static final String TAG = "IModbusWorker";

    private static final String NO_INIT_MESSAGE = "ModbusMaster hasn't been inited!";

    private final ExecutorService mRequestExecutor;

    protected ModbusMaster mModbusMaster;
    private long mSendTime;
    private long mSendIntervalTime;

    public ModbusWorker() {
        // modbus请求用的单一线程池
        mRequestExecutor = Executors.newSingleThreadExecutor();
    }

    /**
     * 关掉ModbusMaster
     */
    @Override
    public synchronized void closeModbusMaster() {
        if (mModbusMaster != null) {
            try {
                mModbusMaster.destroy();
            } catch (Exception e) {
                //throw new RuntimeException(e);
            }
            mModbusMaster = null;
        }
    }

    /**
     * 释放整个ModbusWorker，ModbusScheduler会被释放，ModbusMaster无法再次被打开
     */
    @Override
    public synchronized void release() {
        closeModbusMaster();
        mRequestExecutor.shutdown();
    }

    @Override
    public synchronized ModbusMaster getModbusMaster() {
        return mModbusMaster;
    }

    @Override
    public synchronized boolean isModbusOpened() {
        return getModbusMaster() != null;
    }

    @Override
    public void checkWorkingState() throws ModbusInitException, IllegalStateException {

        if (mModbusMaster == null) {
            throw new ModbusInitException(NO_INIT_MESSAGE);
        }
    }

    //<editor-fold desc="一些通用的方法">

    /**
     * 发送命令间隔时间
     *
     * @return
     */
    protected long getSendIntervalTime() {
        return mSendIntervalTime;
    }

    /**
     * 设置两次发送命令之间必须要等待的时间
     *
     * @param ms
     * @return
     */
    public void setSendIntervalTime(long ms) {

        if (ms < 0) {
            throw new IllegalArgumentException(
                "Send interval time should not be negative, but now ms=" + ms);
        }

        mSendIntervalTime = ms;
    }

    /**
     * 通用的同步方法
     *
     * @param callable
     * @param <T>
     * @return
     * @throws InterruptedException
     * @throws ModbusInitException
     * @throws ModbusTransportException
     * @throws ModbusRespException
     * @throws ExecutionException
     */
    public <T> T doSync(final Callable<T> callable)
        throws InterruptedException, ModbusInitException, ModbusTransportException,
        ModbusRespException, ExecutionException {

        Future<T> submit = null;
        try {

            Callable<T> finalCallable = callable;

            if (getSendIntervalTime() > 0) {
                finalCallable = new Callable<T>() {
                    @Override
                    public T call() throws Exception {
                        if (mSendTime > 0) {
                            long offset =
                                (getSendIntervalTime() - SystemClock.uptimeMillis() - mSendTime);
                            if (offset > 0) {
                                SystemClock.sleep(offset);
                            }
                        }
                        T result = callable.call();
                        mSendTime = SystemClock.uptimeMillis();
                        return result;
                    }
                };
            }
            submit = mRequestExecutor.submit(finalCallable);
            return submit.get();
        } catch (InterruptedException e) {
            if (submit != null) {
                submit.cancel(true);
            }
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            //e.printStackTrace();
            Throwable cause = e.getCause();
            if (cause instanceof ModbusInitException) {
                throw ((ModbusInitException) cause);
            } else if (cause instanceof ModbusTransportException) {
                throw ((ModbusTransportException) cause);
            } else if (cause instanceof ModbusRespException) {
                throw ((ModbusRespException) cause);
            } else {
                throw e;
            }
        }
    }

    /** 异步执行任务 */
    @SuppressLint("StaticFieldLeak")
    protected class AsyncCallableTask<T> extends AsyncTask<Void, Void, Object> {

        private final Callable<T> mCallable;
        private final ModbusCallback<T> mCallback;

        public AsyncCallableTask(Callable<T> callable, ModbusCallback<T> callback) {
            mCallable = callable;
            mCallback = callback;
        }

        @Override
        protected Object doInBackground(Void... voids) {
            try {
                return doSync(mCallable);
            } catch (Throwable e) {
                return e;
            }
        }

        @Override
        protected void onPostExecute(Object o) {
            if (o instanceof Throwable) {
                mCallback.onFailure((Throwable) o);
            } else {
                mCallback.onSuccess((T) o);
            }
            mCallback.onFinally();
        }
    }
    //</editor-fold>

    //<editor-fold desc="初始化Modbbus代码">

    /**
     * 初始化的Callable
     *
     * @param param
     * @return
     */
    @NonNull
    public Callable<ModbusMaster> callableInit(final ModbusParam param) {
        return new Callable<ModbusMaster>() {
            @Override
            public ModbusMaster call() throws Exception {

                // 重置发送时间
                mSendTime = 0;

                if (mModbusMaster != null) {
                    mModbusMaster.destroy();
                    mModbusMaster = null;
                }

                ModbusMaster master = param.createModbusMaster();

                try {
                    if (master == null) {
                        throw new ModbusInitException("Invalid ModbusParam!");
                    }
                    master.init();
                } catch (ModbusInitException e) {

                    Log.w(TAG, "ModbusMaster init failed", e);

                    if (master != null) {
                        master.destroy();
                    }
                    // 再抛出异常
                    throw e;
                }

                mModbusMaster = master;
                return master;
            }
        };
    }

    /**
     * 初始化modbus
     *
     * @param param
     * @return
     * @throws ModbusInitException
     */
    @NonNull
    public synchronized ModbusMaster syncInit(final ModbusParam param)
        throws InterruptedException, ExecutionException, ModbusTransportException,
        ModbusInitException, ModbusRespException {

        return doSync(callableInit(param));
    }

    /**
     * 初始化modbus
     *
     * @param param
     * @param callback
     */
    @Override
    public void init(final ModbusParam param, final ModbusCallback<ModbusMaster> callback) {

        new AsyncCallableTask<>(callableInit(param), callback).execute();
    }
    //</editor-fold>

    //<editor-fold desc="01 (0x01)读线圈">

    @NonNull
    public Callable<ReadCoilsResponse> callableReadCoil(
        final int slaveId, final int start,
        final int len
    ) {
        return new Callable<ReadCoilsResponse>() {
            @Override
            public ReadCoilsResponse call() throws Exception {

                checkWorkingState();

                ReadCoilsRequest request = new ReadCoilsRequest(slaveId, start, len);
                ReadCoilsResponse response = (ReadCoilsResponse) mModbusMaster.send(request);

                if (response.isException()) {
                    throw new ModbusRespException(response);
                }
                return response;
            }
        };
    }

    /**
     * 01 (0x01)读线圈，同步，需在子线程运行
     *
     * @param slaveId 从设备ID
     * @param start 起始地址
     * @param len 线圈数量
     * @return
     * @throws ModbusInitException
     * @throws ModbusTransportException
     * @throws ModbusRespException
     */
    public ReadCoilsResponse syncReadCoil(final int slaveId, final int start, final int len)
        throws InterruptedException, ExecutionException, ModbusTransportException,
        ModbusInitException, ModbusRespException {

        return doSync(callableReadCoil(slaveId, start, len));
    }

    /**
     * 01 (0x01)读线圈
     *
     * @param slaveId 从设备ID
     * @param start 起始地址
     * @param len 线圈数量
     * @param callback
     */
    public void readCoil(
        final int slaveId, final int start, final int len,
        final ModbusCallback<ReadCoilsResponse> callback
    ) {
        new AsyncCallableTask<>(callableReadCoil(slaveId, start, len), callback).execute();
    }
    //</editor-fold>

    //<editor-fold desc="02（0x02）读离散量输入">

    @NonNull
    public Callable<ReadDiscreteInputsResponse> callableReadDiscreteInput(
        final int slaveId,
        final int start, final int len
    ) {
        return new Callable<ReadDiscreteInputsResponse>() {
            @Override
            public ReadDiscreteInputsResponse call() throws Exception {

                checkWorkingState();

                ReadDiscreteInputsRequest request =
                    new ReadDiscreteInputsRequest(slaveId, start, len);
                ReadDiscreteInputsResponse response =
                    (ReadDiscreteInputsResponse) mModbusMaster.send(request);

                if (response.isException()) {
                    throw new ModbusRespException(response);
                }
                return response;
            }
        };
    }

    /**
     * 02（0x02）读离散量输入，同步，需在子线程运行
     *
     * @param slaveId 从设备ID
     * @param start 起始地址
     * @param len 输入数量
     * @return
     * @throws ModbusInitException
     * @throws ModbusTransportException
     * @throws ModbusRespException
     */
    public ReadDiscreteInputsResponse syncReadDiscreteInput(
        final int slaveId, final int start,
        final int len
    ) throws InterruptedException, ExecutionException, ModbusTransportException,
        ModbusInitException, ModbusRespException {

        return doSync(callableReadDiscreteInput(slaveId, start, len));
    }

    /**
     * 02（0x02）读离散量输入
     *
     * @param slaveId 从设备ID
     * @param start 起始地址
     * @param len 输入数量
     * @param callback
     */
    public void readDiscreteInput(
        final int slaveId, final int start, final int len,
        final ModbusCallback<ReadDiscreteInputsResponse> callback
    ) {
        new AsyncCallableTask<>(callableReadDiscreteInput(slaveId, start, len), callback).execute();
    }
    //</editor-fold>

    //<editor-fold desc="03 (0x03)读保持寄存器">

    @NonNull
    public Callable<ReadHoldingRegistersResponse> callableReadHoldingRegisters(
        final int slaveId,
        final int start, final int len
    ) {
        return new Callable<ReadHoldingRegistersResponse>() {
            @Override
            public ReadHoldingRegistersResponse call() throws Exception {

                checkWorkingState();

                ReadHoldingRegistersRequest request =
                    new ReadHoldingRegistersRequest(slaveId, start, len);

                ReadHoldingRegistersResponse response =
                    (ReadHoldingRegistersResponse) mModbusMaster.send(request);

                if (response.isException()) {
                    throw new ModbusRespException(response);
                }
                return response;
            }
        };
    }

    /**
     * 03 (0x03)读保持寄存器，同步，需在子线程运行
     *
     * @param slaveId 从设备ID
     * @param start 起始地址
     * @param len 寄存器数量
     * @return
     * @throws ModbusInitException
     * @throws ModbusTransportException
     * @throws ModbusRespException
     */
    public ReadHoldingRegistersResponse syncReadHoldingRegisters(
        final int slaveId, final int start,
        final int len
    ) throws InterruptedException, ExecutionException, ModbusTransportException,
        ModbusInitException, ModbusRespException {
        return doSync(callableReadHoldingRegisters(slaveId, start, len));
    }

    /**
     * 03 (0x03)读保持寄存器
     *
     * @param slaveId 从设备ID
     * @param start 起始地址
     * @param len 寄存器数量
     * @param callback
     */
    public void readHoldingRegisters(
        final int slaveId, final int start, final int len,
        final ModbusCallback<ReadHoldingRegistersResponse> callback
    ) {
        new AsyncCallableTask<>(
            callableReadHoldingRegisters(slaveId, start, len),
            callback
        ).execute();
    }
    //</editor-fold>

    //<editor-fold desc="04（0x04）读输入寄存器">

    @NonNull
    public Callable<ReadInputRegistersResponse> callableReadInputRegisters(
        final int slaveId,
        final int start, final int len
    ) {
        return new Callable<ReadInputRegistersResponse>() {
            @Override
            public ReadInputRegistersResponse call() throws Exception {

                checkWorkingState();

                ReadInputRegistersRequest request =
                    new ReadInputRegistersRequest(slaveId, start, len);
                ReadInputRegistersResponse response =
                    (ReadInputRegistersResponse) mModbusMaster.send(request);

                if (response.isException()) {
                    throw new ModbusRespException(response);
                }
                return response;
            }
        };
    }

    /**
     * 04（0x04）读输入寄存器，同步，需在子线程运行
     *
     * @param slaveId 从设备ID
     * @param start 起始地址
     * @param len 寄存器数量
     * @return
     * @throws ModbusInitException
     * @throws ModbusTransportException
     * @throws ModbusRespException
     */
    public ReadInputRegistersResponse syncReadInputRegisters(
        final int slaveId, final int start,
        final int len
    ) throws InterruptedException, ExecutionException, ModbusTransportException,
        ModbusInitException, ModbusRespException {

        return doSync(callableReadInputRegisters(slaveId, start, len));
    }

    /**
     * 04（0x04）读输入寄存器
     *
     * @param slaveId 从设备ID
     * @param start 起始地址
     * @param len 寄存器数量
     * @param callback
     */
    public void readInputRegisters(
        final int slaveId, final int start, final int len,
        final ModbusCallback<ReadInputRegistersResponse> callback
    ) {
        new AsyncCallableTask<>(
            callableReadInputRegisters(slaveId, start, len),
            callback
        ).execute();
    }
    //</editor-fold>

    //<editor-fold desc="05（0x05）写单个线圈">

    @NonNull
    public Callable<WriteCoilResponse> callableWriteCoil(
        final int slaveId, final int offset,
        final boolean value
    ) {
        return new Callable<WriteCoilResponse>() {
            @Override
            public WriteCoilResponse call() throws Exception {

                checkWorkingState();

                WriteCoilRequest request = new WriteCoilRequest(slaveId, offset, value);
                WriteCoilResponse response = (WriteCoilResponse) mModbusMaster.send(request);

                if (response.isException()) {
                    throw new ModbusRespException(response);
                }

                return response;
            }
        };
    }

    /**
     * 05（0x05）写单个线圈，同步，需在子线程运行
     *
     * @param slaveId 从设备ID
     * @param offset 输出地址
     * @param value 输出值
     * @return
     * @throws ModbusInitException
     * @throws ModbusTransportException
     * @throws ModbusRespException
     */
    public WriteCoilResponse syncWriteCoil(final int slaveId, final int offset, final boolean value)
        throws InterruptedException, ExecutionException, ModbusTransportException,
        ModbusInitException, ModbusRespException {

        return doSync(callableWriteCoil(slaveId, offset, value));
    }

    /**
     * 05（0x05）写单个线圈
     *
     * @param slaveId 从设备ID
     * @param offset 输出地址
     * @param value 输出值
     * @param callback
     */
    public void writeCoil(
        final int slaveId, final int offset, final boolean value,
        final ModbusCallback<WriteCoilResponse> callback
    ) {

        new AsyncCallableTask<>(callableWriteCoil(slaveId, offset, value), callback).execute();
    }
    //</editor-fold>

    //<editor-fold desc="06（0x06）写单个寄存器">

    @NonNull
    public Callable<WriteRegisterResponse> callableWriteSingleRegister(
        final int slaveId,
        final int offset, final int value
    ) {
        return new Callable<WriteRegisterResponse>() {
            @Override
            public WriteRegisterResponse call() throws Exception {

                checkWorkingState();

                WriteRegisterRequest request = new WriteRegisterRequest(slaveId, offset, value);
                WriteRegisterResponse response =
                    (WriteRegisterResponse) mModbusMaster.send(request);

                if (response.isException()) {
                    throw new ModbusRespException(response);
                }

                return response;
            }
        };
    }

    /**
     * 06 (0x06) 写单个寄存器, 同步，需在子线程运行
     *
     * @param slaveId 从设备ID
     * @param offset 寄存器地址
     * @param value 寄存器值
     * @return
     */
    public WriteRegisterResponse syncWriteSingleRegister(
        final int slaveId, final int offset,
        final int value
    ) throws InterruptedException, ExecutionException, ModbusTransportException,
        ModbusInitException, ModbusRespException {

        return doSync(callableWriteSingleRegister(slaveId, offset, value));
    }

    /**
     * 06 (0x06) 写单个寄存器
     *
     * @param slaveId 从设备ID
     * @param offset 寄存器地址
     * @param value 寄存器值
     */
    public void writeSingleRegister(
        final int slaveId, final int offset, final int value,
        final ModbusCallback<WriteRegisterResponse> callback
    ) {
        new AsyncCallableTask<>(
            callableWriteSingleRegister(slaveId, offset, value),
            callback
        ).execute();
    }

    //</editor-fold>

    //<editor-fold desc="15（0x0F）写多个线圈">

    @NonNull
    public Callable<WriteCoilsResponse> callableWriteCoils(
        final int slaveId, final int start,
        final boolean[] values
    ) {
        return new Callable<WriteCoilsResponse>() {
            @Override
            public WriteCoilsResponse call() throws Exception {

                checkWorkingState();

                WriteCoilsRequest request = new WriteCoilsRequest(slaveId, start, values);
                WriteCoilsResponse response = (WriteCoilsResponse) mModbusMaster.send(request);

                if (response.isException()) {
                    throw new ModbusRespException(response);
                }

                return response;
            }
        };
    }

    /**
     * 15（0x0F）写多个线圈, 同步，需在子线程运行
     *
     * @param slaveId 从设备ID
     * @param start 起始地址
     * @param values 输出值
     * @return
     * @throws ModbusInitException
     * @throws ModbusTransportException
     * @throws ModbusRespException
     */
    public WriteCoilsResponse syncWriteCoils(
        final int slaveId, final int start,
        final boolean[] values
    )
        throws InterruptedException, ExecutionException, ModbusTransportException,
        ModbusInitException, ModbusRespException {

        return doSync(callableWriteCoils(slaveId, start, values));
    }

    /**
     * 15（0x0F）写多个线圈
     *
     * @param slaveId 从设备ID
     * @param start 起始地址
     * @param values 输出值
     */
    public void writeCoils(
        final int slaveId, final int start, final boolean[] values,
        final ModbusCallback<WriteCoilsResponse> callback
    ) {
        new AsyncCallableTask<>(callableWriteCoils(slaveId, start, values), callback).execute();
    }

    //</editor-fold>

    //<editor-fold desc="16（0x10）写多个寄存器">

    @NonNull
    public Callable<WriteRegistersResponse> callableWriteRegisters(
        final int slaveId,
        final int start, final short[] values
    ) {
        return new Callable<WriteRegistersResponse>() {
            @Override
            public WriteRegistersResponse call() throws Exception {

                checkWorkingState();

                WriteRegistersRequest request = new WriteRegistersRequest(slaveId, start, values);
                WriteRegistersResponse response =
                    (WriteRegistersResponse) mModbusMaster.send(request);

                if (response.isException()) {
                    throw new ModbusRespException(response);
                }

                return response;
            }
        };
    }

     //</editor-fold>

    //<editor-fold desc="16（0x10）写多个寄存器">

    @NonNull
    public Callable<CustomWriteRegistersResponse> callableCustomWriteRegisters(
        final int slaveId,
        final int start, final int[] values
    ) {
        return new Callable<CustomWriteRegistersResponse>() {
            @Override
            public CustomWriteRegistersResponse call() throws Exception {

                checkWorkingState();

                CustomWriteRegistersRequest request = new CustomWriteRegistersRequest(slaveId, start, values);
                CustomWriteRegistersResponse response =
                    (CustomWriteRegistersResponse) mModbusMaster.send(request);

                if (response.isException()) {
                    throw new ModbusRespException(response);
                }

                return response;
            }
        };
    }

    /**
     * 16 (0x10) 写多个寄存器, 同步，需在子线程运行
     *
     * @param slaveId 从设备ID
     * @param start 开始寄存器地址
     * @param values 寄存器值
     * @return
     */
    public WriteRegistersResponse syncWriteRegisters(
        final int slaveId, final int start,
        final short[] values
    )
        throws InterruptedException, ExecutionException, ModbusTransportException,
        ModbusInitException, ModbusRespException {

        return doSync(callableWriteRegisters(slaveId, start, values));
    }

    /**
     * 16 (0x10) 写多个寄存器
     *
     * @param slaveId 从设备ID
     * @param start 开始寄存器地址
     * @param values 寄存器值
     */
    public void writeRegisters(
        final int slaveId, final int start, final short[] values,
        final ModbusCallback<WriteRegistersResponse> callback
    ) {
        new AsyncCallableTask<>(callableWriteRegisters(slaveId, start, values), callback).execute();
    }

    /**
     * 16 (0x10) 写多个寄存器
     *
     * @param slaveId 从设备ID
     * @param start 开始寄存器地址
     * @param values 寄存器值
     */
    public void customWriteRegisters(
        final int slaveId, final int start, final int[] values,
        final ModbusCallback<CustomWriteRegistersResponse> callback
    ) {
        new AsyncCallableTask<>(callableCustomWriteRegisters(slaveId, start, values), callback).execute();
    }
    
    //</editor-fold>

    //<editor-fold desc="16（0x10）写多个寄存器，但只写1个">

    /**
     * 16（0x10）写多个寄存器，但只写1个, 同步，需在子线程运行
     *
     * @param slaveId 从设备ID
     * @param offset 寄存器地址
     * @param value 寄存器值
     * @return
     */
    public WriteRegistersResponse syncWriteRegistersButOne(
        final int slaveId, final int offset,
        final int value
    ) throws Exception {

        short[] shorts = { (short) value };
        return syncWriteRegisters(slaveId, offset, shorts);
    }

    /**
     * 16（0x10）写多个寄存器，但只写1个
     *
     * @param slaveId 从设备ID
     * @param start 开始寄存器地址
     * @param value 寄存器值
     */
    public void writeRegistersButOne(
        final int slaveId, final int start, final int value,
        final ModbusCallback<WriteRegistersResponse> callback
    ) {
        writeRegisters(slaveId, start, new short[] { (short) value }, callback);
    }
    //</editor-fold>
}
