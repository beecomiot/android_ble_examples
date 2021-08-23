/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.bluetoothlegatt;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.Telephony;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.telephony.PhoneStateListener;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ExpandableListView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SimpleExpandableListAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.sql.Time;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

/**
 * For a given BLE device, this Activity provides the user interface to connect, display data,
 * and display GATT services and characteristics supported by the device.  The Activity
 * communicates with {@code BluetoothLeService}, which in turn interacts with the
 * Bluetooth LE API.
 */
public class DeviceControlActivity extends Activity {

    public static final String SMS_INTENT = "com.example.android.bluetoothlegatt.sms";
    private final int READ_PERIOD = 1000;

    private final static String TAG = DeviceControlActivity.class.getSimpleName();

    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";

    private static final int MODE_1 = 13;
    private static final int MODE_2 = 15;
    private static final int MODE_3 = 18;

    private static final int STRENGTH_MIN = 4;
    private static final int STRENGTH_MAX = 240;
    private int strength = STRENGTH_MIN*2;
    private int mode = MODE_1;

    private LinearLayout workLayout;
    private ProgressBar progressBar;

    private TextView heartRate;
    private TextView blood;
    private TextView steps;
    private TextView dateAndTime;
    private Button dateTimeUpdate;

//    private TextView workTextView;
//    private Button startButton;
//    private Button stopButton;
//    private Button modeButton;
//    private Button plusButton;
//    private Button minusButton;

    private TextView mConnectionState;
    private TextView mDataField;
    private String mDeviceName;
    private String mDeviceAddress;
    private ExpandableListView mGattServicesList;
    private BluetoothLeService mBluetoothLeService;
    private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics =
            new ArrayList<ArrayList<BluetoothGattCharacteristic>>();
    private boolean mConnected = false;
    private boolean isAppFront = false;
    private BluetoothGattCharacteristic mNotifyCharacteristic;

    private final String LIST_NAME = "NAME";
    private final String LIST_UUID = "UUID";

    private final MyHandler mHandler = new MyHandler(this);

    private TelephonyManager telephonyManager;
    private MyPhoneStateListener myPhoneStateListener;




    void setStrength(int s) {
        if(0 == s) {
            strength = 0;
        } else {
            if (s < STRENGTH_MIN) s = STRENGTH_MIN;
            if (s > STRENGTH_MAX) s = STRENGTH_MAX;
            strength = s;
        }
    }

    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(mDeviceAddress);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
                updateConnectionState(R.string.connected);
                invalidateOptionsMenu();
                displayProgress();
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                updateConnectionState(R.string.disconnected);
                invalidateOptionsMenu();
                clearUI();
                displayProgress();
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                displayGattServices(mBluetoothLeService.getSupportedGattServices());
                displayWorkLayout();
                // enableNotify();
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                // displayData(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
                byte[] data = intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA);

                parseNotification(data);
            }
        }
    };

    private final BroadcastReceiver mSmsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if(intent.getAction().equals("android.provider.Telephony.SMS_RECEIVED")){
                Bundle bundle = intent.getExtras();           //---get the SMS message passed in---
                SmsMessage[] msgs = null;
                String msg_from;
                if (bundle != null){
                    Log.d("SMS", "Got the SMS");
                    byte[] data = new byte[]{0x01, 1, 3, 'x', 'y', 'z', 0x00, 0x00, 0x00, 0x00,
                            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
                    mBluetoothLeService.writeBleData(data);
                }
            }
        }
    };

    // If a given GATT characteristic is selected, check for supported features.  This sample
    // demonstrates 'Read' and 'Notify' features.  See
    // http://d.android.com/reference/android/bluetooth/BluetoothGatt.html for the complete
    // list of supported characteristic features.
    private final ExpandableListView.OnChildClickListener servicesListClickListner =
            new ExpandableListView.OnChildClickListener() {
                @Override
                public boolean onChildClick(ExpandableListView parent, View v, int groupPosition,
                                            int childPosition, long id) {
                    if (mGattCharacteristics != null) {
                        final BluetoothGattCharacteristic characteristic =
                                mGattCharacteristics.get(groupPosition).get(childPosition);
                        final int charaProp = characteristic.getProperties();
                        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
                            // If there is an active notification on a characteristic, clear
                            // it first so it doesn't update the data field on the user interface.
                            if (mNotifyCharacteristic != null) {
                                mBluetoothLeService.setCharacteristicNotification(
                                        mNotifyCharacteristic, false);
                                mNotifyCharacteristic = null;
                            }
                            mBluetoothLeService.readCharacteristic(characteristic);
                        }
                        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                            mNotifyCharacteristic = characteristic;
                            mBluetoothLeService.setCharacteristicNotification(
                                    characteristic, true);
                        }
                        return true;
                    }
                    return false;
                }
    };

    private void clearUI() {
        mGattServicesList.setAdapter((SimpleExpandableListAdapter) null);
        mDataField.setText(R.string.no_data);
    }

    private byte[] updateDateTimeType() {
        Calendar ca = Calendar.getInstance();
        int year = ca.get(Calendar.YEAR);
        int month = ca.get(Calendar.MONTH) + 1;
        int day = ca.get(Calendar.DATE);
        int hour = ca.get(Calendar.HOUR_OF_DAY);
        int minute = ca.get(Calendar.MINUTE);
        int second = ca.get(Calendar.SECOND);

        if(year < 2021) {
            year = 2021;
        }
        year = year - 2000;

        byte[] data = new byte[] {0x00, (byte)(year & 0xFF), (byte)(month & 0xFF), (byte)(day & 0xFF), (byte)(hour & 0xFF), (byte)(minute & 0xFF), (byte)(second & 0xFF), 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        return data;
    }

    private byte[] phonecallType(boolean enable, String phone) {

        byte e = enable ? (byte)0x01 : (byte)0;
        byte[] data;

        if(enable && phone != null && phone.length() > 0) {
            byte[] phoneArray = phone.getBytes();
            data = new byte[]{0x01, e, (byte)phoneArray.length, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};

            for(int i = 0; i < phoneArray.length && i < 17; i++) {
                data[3 + i] = phoneArray[i];
            }
        } else {
            data = new byte[]{0x01, e, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        }
        return data;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gatt_services_characteristics);

        final Intent intent = getIntent();
        mDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
        mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);

        // Sets up UI references.
        workLayout = findViewById(R.id.work_layout);
        progressBar = findViewById(R.id.loading_progress);
//        workTextView = findViewById(R.id.work_text);
//        startButton = findViewById(R.id.start_button);
//        stopButton = findViewById(R.id.stop_button);
//        modeButton = findViewById(R.id.mode_button);
//        plusButton = findViewById(R.id.strength_plus);
//        minusButton = findViewById(R.id.strength_minus);

        heartRate = findViewById(R.id.heart_rate_value);
        blood = findViewById(R.id.blood_value);
        steps = findViewById(R.id.steps_value);
        dateAndTime = findViewById(R.id.time_value);
        dateTimeUpdate = findViewById(R.id.update_time);

//        startButton.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View view) {
//                mBluetoothLeService.writeBleData(updateData());
//            }
//        });
//
//        plusButton.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View view) {
//                int s = strength;
//                s++;
//                setStrength(s);
//                mBluetoothLeService.writeBleData(updateData());
//            }
//        });
//
//        minusButton.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View view) {
//                int s = strength;
//                s--;
//                setStrength(s);
//                mBluetoothLeService.writeBleData(updateData());
//            }
//        });
//
//        stopButton.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View view) {
//                setStrength(0);
//                mBluetoothLeService.writeBleData(updateData());
//            }
//        });
//
//        modeButton.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View view) {
//                switch (mode) {
//                    case MODE_1:
//                    mode = MODE_2;
//                    break;
//                    case MODE_2:
//                        mode = MODE_3;
//                        break;
//                    case MODE_3:
//                        mode = MODE_1;
//                        break;
//                    default:
//                        mode = MODE_1;
//                        break;
//                }
//                mBluetoothLeService.writeBleData(updateData());
//            }
//        });

        dateTimeUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mBluetoothLeService.writeBleData(updateDateTimeType());
                // mBluetoothLeService.readBleData();
            }
        });

        ((TextView) findViewById(R.id.device_address)).setText(mDeviceAddress);
        mGattServicesList = (ExpandableListView) findViewById(R.id.gatt_services_list);
        mGattServicesList.setOnChildClickListener(servicesListClickListner);
        mConnectionState = (TextView) findViewById(R.id.connection_state);
        mDataField = (TextView) findViewById(R.id.data_value);

        getActionBar().setTitle(mDeviceName);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

        displayProgress();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_PHONE_STATE)  != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.READ_CALL_LOG,
                                Manifest.permission.RECEIVE_SMS},
                        DeviceScanActivity.PERMISSION_READ_STATE);
            }
        }

        Message message = Message.obtain(mHandler);
        mHandler.sendMessageDelayed(message, READ_PERIOD);

        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        myPhoneStateListener = new MyPhoneStateListener();
        telephonyManager.listen(myPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

        final IntentFilter smsIntentFilter = new IntentFilter();
        smsIntentFilter.addAction("android.provider.Telephony.SMS_RECEIVED");
        registerReceiver(mSmsReceiver, smsIntentFilter);

    }

    @Override
    protected void onResume() {
        super.onResume();
        isAppFront = true;
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBluetoothLeService != null) {
            final boolean result = mBluetoothLeService.connect(mDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
        isAppFront = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(mServiceConnection);
        mBluetoothLeService = null;
        telephonyManager.listen(myPhoneStateListener, PhoneStateListener.LISTEN_NONE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gatt_services, menu);
        if (mConnected) {
            menu.findItem(R.id.menu_connect).setVisible(false);
            menu.findItem(R.id.menu_disconnect).setVisible(true);
        } else {
            menu.findItem(R.id.menu_connect).setVisible(true);
            menu.findItem(R.id.menu_disconnect).setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.menu_connect:
                mBluetoothLeService.connect(mDeviceAddress);
                return true;
            case R.id.menu_disconnect:
                mBluetoothLeService.disconnect();
                onBackPressed();
                return true;
            case android.R.id.home:
                mBluetoothLeService.disconnect();
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateConnectionState(final int resourceId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mConnectionState.setText(resourceId);
            }
        });
    }

    private void displayData(String data) {
        if (data != null) {
            mDataField.setText(data);
            // Log.d(TAG, data);
        }
    }

    private void parseNotification(byte[] data) {
        if(null == data) {
            return;
        }
        final StringBuilder stringBuilder = new StringBuilder(data.length);
        for(byte byteChar : data)
            stringBuilder.append(String.format("%02X ", byteChar));

        Long tmp = new Long(0);
        String str = new String();
        int i = 0;
        Log.d("PARSING", stringBuilder.toString());
        if(data.length < 13) return;

        tmp = (long)data[i++] & 0xFF;
        str = tmp == 0 ? getString(R.string.value_null) : tmp.toString();
        heartRate.setText(str);

        tmp = (long)data[i++] & 0xFF;
        str = (tmp == 0 ? getString(R.string.value_null) : tmp.toString())+ "/";
        tmp = (long)data[i++] & 0xFF;
        str += tmp == 0 ? getString(R.string.value_null) : tmp.toString();
        blood.setText(str);

        tmp = (long)data[i++] & 0xFF;
        tmp = (tmp << 8) + ((long)data[i++] & 0xFF);
        tmp = (tmp << 8) + ((long)data[i++] & 0xFF);
        tmp = (tmp << 8) + ((long)data[i++] & 0xFF);
        str = tmp.toString();
        steps.setText(str);

        tmp =2000 + ((long)data[i++] & 0xFF);
        str = tmp.toString() + ":";
        tmp = (long)data[i++] & 0xFF;
        // str += tmp.toString() + ":";
        str += String.format("%02d", tmp) + ":";
        tmp = (long)data[i++] & 0xFF;
        // str += tmp.toString() + ",";
        str += String.format("%02d", tmp) + ", ";
        tmp = (long)data[i++] & 0xFF;
        // str += tmp.toString() + ":";
        str += String.format("%02d", tmp) + ":";
        tmp = (long)data[i++] & 0xFF;
        // str += tmp.toString() + ":";
        str += String.format("%02d", tmp) + ":";
        tmp = (long)data[i++] & 0xFF;
        // str += tmp.toString();
        str += String.format("%02d", tmp);

        dateAndTime.setText(str);

//        String workText = getString(R.string.idle);
//
//        if(data.length >= 20) {
//            if(data[8] != 0 && data[9] != 0 && strength != 0) {
//                String modeStr;
//                switch (data[13])
//                {
//                    case MODE_1:
//                        modeStr = "1";
//                        break;
//                    case MODE_2:
//                        modeStr = "2";
//                        break;
//                    case MODE_3:
//                        modeStr = "3";
//                        break;
//                    default:
//                        modeStr = "1";
//                        break;
//                }
//                workText = getString(R.string.mode) + ":" + modeStr + "  " + getString(R.string.strength) + ":" + data[18];
//            } else {
//                workText = getString(R.string.idle);
//            }
//
//        }

        // workTextView.setText(workText);
    }

    // Demonstrates how to iterate through the supported GATT Services/Characteristics.
    // In this sample, we populate the data structure that is bound to the ExpandableListView
    // on the UI.
    private void displayGattServices(List<BluetoothGattService> gattServices) {
        if (gattServices == null) return;
        String uuid = null;
        String unknownServiceString = getResources().getString(R.string.unknown_service);
        String unknownCharaString = getResources().getString(R.string.unknown_characteristic);
        ArrayList<HashMap<String, String>> gattServiceData = new ArrayList<HashMap<String, String>>();
        ArrayList<ArrayList<HashMap<String, String>>> gattCharacteristicData
                = new ArrayList<ArrayList<HashMap<String, String>>>();
        mGattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

        // Loops through available GATT Services.
        for (BluetoothGattService gattService : gattServices) {
            HashMap<String, String> currentServiceData = new HashMap<String, String>();
            uuid = gattService.getUuid().toString();
            currentServiceData.put(
                    LIST_NAME, SampleGattAttributes.lookup(uuid, unknownServiceString));
            currentServiceData.put(LIST_UUID, uuid);
            gattServiceData.add(currentServiceData);

            ArrayList<HashMap<String, String>> gattCharacteristicGroupData =
                    new ArrayList<HashMap<String, String>>();
            List<BluetoothGattCharacteristic> gattCharacteristics =
                    gattService.getCharacteristics();
            ArrayList<BluetoothGattCharacteristic> charas =
                    new ArrayList<BluetoothGattCharacteristic>();

            // Loops through available Characteristics.
            for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                charas.add(gattCharacteristic);
                HashMap<String, String> currentCharaData = new HashMap<String, String>();
                uuid = gattCharacteristic.getUuid().toString();
                currentCharaData.put(
                        LIST_NAME, SampleGattAttributes.lookup(uuid, unknownCharaString));
                currentCharaData.put(LIST_UUID, uuid);
                gattCharacteristicGroupData.add(currentCharaData);
            }
            mGattCharacteristics.add(charas);
            gattCharacteristicData.add(gattCharacteristicGroupData);
        }

        SimpleExpandableListAdapter gattServiceAdapter = new SimpleExpandableListAdapter(
                this,
                gattServiceData,
                android.R.layout.simple_expandable_list_item_2,
                new String[] {LIST_NAME, LIST_UUID},
                new int[] { android.R.id.text1, android.R.id.text2 },
                gattCharacteristicData,
                android.R.layout.simple_expandable_list_item_2,
                new String[] {LIST_NAME, LIST_UUID},
                new int[] { android.R.id.text1, android.R.id.text2 }
        );
        mGattServicesList.setAdapter(gattServiceAdapter);
    }

    private void displayWorkLayout() {
        progressBar.setVisibility(View.INVISIBLE);
        workLayout.setVisibility(View.VISIBLE);
    }

    private void displayProgress() {
        progressBar.setVisibility(View.VISIBLE);
        workLayout.setVisibility(View.INVISIBLE);
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(DeviceControlActivity.SMS_INTENT);
        return intentFilter;
    }

    private void enableNotify() {
        for (ArrayList<BluetoothGattCharacteristic> array : mGattCharacteristics) {

            for(BluetoothGattCharacteristic characteristic : array) {
                int charaProp = characteristic.getProperties();
                if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                    mNotifyCharacteristic = characteristic;
                    mBluetoothLeService.setCharacteristicNotification(
                            characteristic, true);

                    mNotifyCharacteristic = characteristic;
                    mBluetoothLeService.setCharacteristicNotification(
                            characteristic, true);
                }
            }
        }
    }

    private byte[] packData(boolean isStart, int mode, int strength) {
        return null;
    }

    private static class MyHandler extends Handler {
        WeakReference<DeviceControlActivity> mWeakActivity;

        public MyHandler(DeviceControlActivity activity) {
            this.mWeakActivity = new WeakReference<DeviceControlActivity>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            final DeviceControlActivity mActivity = mWeakActivity.get();
            if (mActivity != null) {
                mActivity.msgHandler(msg);
            }
        }
    }

    public void msgHandler(Message msg) {
        try {
            if(isAppFront) {
                if (mConnected) {
                    Log.d("MSG", "READ MSG");
                    mBluetoothLeService.readBleData();
                }
            }

        } catch (Exception e) {

        }

        Message message = Message.obtain(mHandler);
        mHandler.sendMessageDelayed(message, READ_PERIOD);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == DeviceScanActivity.PERMISSION_READ_STATE && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    class MyPhoneStateListener extends PhoneStateListener {
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            super.onCallStateChanged(state, incomingNumber);
            switch (state) {
                // 如果电话铃响
                case TelephonyManager.CALL_STATE_RINGING:
                    Log.d("INCOMING", incomingNumber);
                    if (mConnected) {
                        mBluetoothLeService.writeBleData(phonecallType(true, incomingNumber));
                    }
                    break;
                default:
                    Log.d("INCOMING", incomingNumber);
                    if (mConnected) {
                        mBluetoothLeService.writeBleData(phonecallType(false, incomingNumber));
                    }
                    break;
            }
        }
    }
}
