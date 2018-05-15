package com.android.server.innoservice;

import android.app.KeyguardManager;

import android.content.BroadcastReceiver;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;

import android.os.IBinder;
import android.os.PowerManager;

import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;


import com.android.internal.logging.MetricsLogger;
import com.android.server.SystemService;
import java.util.Timer;
import java.util.TimerTask;

import android.view.OrientationEventListener;
import android.provider.MediaStore;
import android.os.Vibrator;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManager.RunningServiceInfo;
import android.content.pm.ApplicationInfo;
import java.util.List;
import android.os.Handler;
import android.os.Message;
import android.content.pm.ActivityInfo;

public class RaiseTakePhotoService extends SystemService implements IBinder.DeathRecipient {

    private static final String TAG = "RaiseTakePhotoService";
    private Context mContext;

    private SensorManager mSensorManager;
    private Sensor mPickUpCameraSensor;
    private Sensor mProximityCameraSensor;

    private boolean IS_NEED_CHECK_ORSENSOR = android.os.SystemProperties.getBoolean("persist.check.orsensor", false);
    private int CHECK_TIME = android.os.SystemProperties.getInt("persist.check.timer", 50);
    private int LAUNCH_CAMERA_DELAY_TIME = android.os.SystemProperties.getInt("persist.launch_camera.delay_timer", 200);
    private ScreenBroadcastReceiver mScreenReceiver;

    private Vibrator mVibrator;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakelock = null;
    private KeyguardManager.KeyguardLock mKeyguardLock = null;

   private CameraOrEventListener mLaunchCameraOrEventListener;
   private boolean mCurrentOr = false; //get current orientation value
   private boolean mIsProximityMasking = false; //is p-sensor mask
   private boolean mDetectScreenOr = false; //is PickUp event trigger
   private int mDeviceOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; //set portrait mode as default
   private long mTimeLaunchRegistered; //record the trigger event time

   private String CAMERA_PACKAGE_NAME = "com.myos.camera";

   private int PICK_UP_EVENT_RAISE_TO_WAKE = 1;
   private int PICK_UP_EVENT_RAISE_TO_PHOTO = 2;

   private static final int MSG_LAUNCH_CAMERA = 100;
   private static final int MSG_RESET_SENSOR = 101;

    public RaiseTakePhotoService(Context context) {
        super(context);

        mContext = context;
        mScreenReceiver = new ScreenBroadcastReceiver();

        startObserver();

	//obtain service
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
	mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);

	//register sensor
	mPickUpCameraSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PICK_UP_GESTURE);
	mProximityCameraSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);

	//init orientation sensor
        boolean isAllowLaunchCamera = isEnableLandscapeLaunchCamera();
	Log.d(TAG, "isAllowToLaunchCamera: " +isAllowLaunchCamera);

	mLaunchCameraOrEventListener =
        	new CameraOrEventListener(mContext, SensorManager.SENSOR_DELAY_FASTEST);
	mLaunchCameraOrEventListener.disable();
    }

    @Override
    public void binderDied() {
        Slog.v(TAG, "RaiseTakePhotoService died");
        MetricsLogger.count(mContext, "RaiseTakePhotoService", 1);
    }

    public void screenOn() {
        Log.d(TAG, "screen on");
        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
                | PowerManager.ON_AFTER_RELEASE, "TAG");
        wakeLock.acquire();
        wakeLock.release();
    }

    public void startObserver() {
        registerListener();
    }

    public void shutdownObserver() {
        unregisterListener();
    }

    private void registerListener() {
        if (mContext != null) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            mContext.registerReceiver(mScreenReceiver, filter);
        }
    }

    private void unregisterListener() {
        if (mContext != null)
            mContext.unregisterReceiver(mScreenReceiver);
    }

    @Override
    public void onStart() {
    }

    private class ScreenBroadcastReceiver extends BroadcastReceiver {
        private String mAction = null;

        @Override
        public void onReceive(Context context, Intent intent) {
            mAction = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(mAction)) {
                Log.d(TAG, "ACTION_SCREEN_ON");
            } else if (Intent.ACTION_SCREEN_OFF.equals(mAction)) {
                Log.d(TAG, "ACTION_SCREEN_OFF, request to trigger PickUp camera sensor");
		//enable pick up and proximity sensor.
		requestTriggerPickUpCameraSensor();
            } else if (Intent.ACTION_USER_PRESENT.equals(mAction)) {
                Log.d(TAG, "ACTION_USER_PRESENT");
            }
        }
    }

	private void requestTriggerPickUpCameraSensor(){
		Log.d(TAG, "requestTriggerPickUpCameraSensor");

        	boolean isAllowLaunchCamera = isEnableLandscapeLaunchCamera();
        	if (mSensorManager != null && mCameraTriggerEventListener != null && mPickUpCameraSensor != null && isAllowLaunchCamera) {
			Log.d(TAG, "register Pick-Up sensor");
            		mSensorManager.requestTriggerSensor(mCameraTriggerEventListener, mPickUpCameraSensor);
			mDetectScreenOr = false;
        	}
    	}

	private void cancelTriggerPickUpCameraSensor(){
		Log.d(TAG, "cancelTriggerPickUpCameraSensor");
        	if (mSensorManager != null && mPickUpCameraSensor != null ) {
            		mSensorManager.cancelTriggerSensor(mCameraTriggerEventListener, mPickUpCameraSensor);
			mSensorManager.unregisterListener(mProximityCameraEventListener);
        	}
		mLaunchCameraOrEventListener.disable();
    	}

    	private TriggerEventListener mCameraTriggerEventListener = new TriggerEventListener() {
        	public void onTrigger(TriggerEvent triggerEvent) {
			int triggerEventValue = (int) triggerEvent.values[0];
			Log.d(TAG, "mCameraTriggerEventListener, Event Value: "+triggerEventValue );
			mLaunchCameraOrEventListener.enable();
			mDeviceOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
			isLaunchCameraPossible(triggerEventValue);
        	}
    	};

    	private SensorEventListener mProximityCameraEventListener = new SensorEventListener() {
        	@Override
        	public void onSensorChanged(SensorEvent event) {
            		float  mCameraDistance = event.values[0];
            		Log.d(TAG, "camera p-sensor distance: " + mCameraDistance);

            		if( mCameraDistance ==0) {
                		mIsProximityMasking = true;
            		}else{
                		mIsProximityMasking = false;
            		}
			Log.d(TAG, "mProximityCameraEventListener: mIsProximityMasking : " + mIsProximityMasking);
        	}
        	@Override
        		public void onAccuracyChanged(Sensor sensor, int accuracy) {
        	}
    	};

	private boolean isEnableLandscapeLaunchCamera(){
		//get value of UI from settings
   		return Settings.Secure.getIntForUser(mContext.getContentResolver(), "enable_landscape_launch_camera", 1,UserHandle.USER_CURRENT_OR_SELF) != 0;
   	}

	private void launchCamera(){
		screenOn();
		if (null != mVibrator && mVibrator.hasVibrator()) {
            		mVibrator.vibrate(new long[]{100, 100}, -1);
        	} else {
            		Log.v(TAG, "no vibrator?");
        	}
		Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)
					.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
		mContext.startActivity(intent);
		Log.d(TAG,"=== launch camera ===" ); 
   	}

	private void isLaunchCameraPossible(int eventType) {
		Log.d(TAG, "isLaunchCameraPossible");

		 KeyguardManager km = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
		Log.d(TAG, "Device is locked ? " +km.inKeyguardRestrictedInputMode());
        	if (!km.inKeyguardRestrictedInputMode()) {
			return;
        	}

		if(eventType == PICK_UP_EVENT_RAISE_TO_PHOTO  && !isCameraRunning()){
			mSensorManager.registerListener(mProximityCameraEventListener, mProximityCameraSensor, mSensorManager.SENSOR_DELAY_FASTEST);
			if(IS_NEED_CHECK_ORSENSOR){
			 	mDeviceOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
				mTimeLaunchRegistered = System.currentTimeMillis();
				Log.d(TAG, "set trigger time: " +mTimeLaunchRegistered);
			}
			mDetectScreenOr = true;
			mCurrentOr =false;
			mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_LAUNCH_CAMERA),LAUNCH_CAMERA_DELAY_TIME);
		}else {
			mHandler.sendMessage(mHandler.obtainMessage(MSG_RESET_SENSOR));
		}
	}

	private boolean isCameraRunning(){
		boolean isRunning = false;
		int uid = getPackageUid(mContext, CAMERA_PACKAGE_NAME);
		if(uid > 0){
  			boolean rstA = isAppRunning(mContext, CAMERA_PACKAGE_NAME);
  			boolean rstB = isProcessRunning(mContext, uid);
  			if(rstA||rstB){
      				isRunning =true;
  			}else{
  				isRunning =false;
	  		}
		}else{
			isRunning =false;
		}
		Log.d(TAG, "Camera is running : "  + isRunning);
		return isRunning;
	}

	public static boolean isAppRunning(Context mContext, String packageName) {
       		ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
      		 List<ActivityManager.RunningTaskInfo> list = am.getRunningTasks(100);
       		if (list.size() <= 0) {
           		return false;
       		}
       		for (ActivityManager.RunningTaskInfo info : list) {
           		if (info.baseActivity.getPackageName().equals(packageName)) {
               			return true;
           		}
       		}
       		return false;
   	}

	public static int getPackageUid(Context context, String packageName) {
       		try {
           		ApplicationInfo applicationInfo = context.getPackageManager().getApplicationInfo(packageName, 0);
           		if (applicationInfo != null) {
               			return applicationInfo.uid;
           		}
       		} catch (Exception e) {
           		return -1;
       		}
       		return -1;
   	}

	public static boolean isProcessRunning(Context context, int uid) {
       		ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
      		 List<ActivityManager.RunningServiceInfo> runningServiceInfos = am.getRunningServices(200);
       		if (runningServiceInfos.size() > 0) {
           		for (ActivityManager.RunningServiceInfo appProcess : runningServiceInfos){
               			if (uid == appProcess.uid) {
                   			return true;
               			}
           		}
       		}
       		return false;
   	}

	private final Handler mHandler = new Handler() {
			@Override
			public void handleMessage(Message msg) {
				switch (msg.what) {
					case MSG_LAUNCH_CAMERA:
						Log.d(TAG, "MSG_LAUNCH_CAMERA");
						boolean isAllowLaunchCamera = isEnableLandscapeLaunchCamera();
						Log.d(TAG, "check all state whether device can launch camera, isAllowLaunchCamera: " + isAllowLaunchCamera + " ,DeviceOrientation: " + mDeviceOrientation +
							" ,mDetectScreenOr: " + mDetectScreenOr + " ,IsProximityMasking: " + mIsProximityMasking);

						if (isAllowLaunchCamera && !mIsProximityMasking  && !isCameraRunning() && 
								(mDeviceOrientation ==  ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||mDeviceOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)
								&& mDetectScreenOr){
							launchCamera();
						} else {
							Log.d(TAG,"NOT allow to launch camera!" ); 
						}
						cancelTriggerPickUpCameraSensor();
						requestTriggerPickUpCameraSensor();
						break;
				case MSG_RESET_SENSOR:
						Log.d(TAG, "MSG_RESET_SENSOR");
						cancelTriggerPickUpCameraSensor();
						requestTriggerPickUpCameraSensor();
						break;
				}
			}
	};

	 private class CameraOrEventListener extends OrientationEventListener{

		public CameraOrEventListener(Context context, int rate) {
			super(context, rate);
			// TODO Auto-generated constructor stub
		}

		@Override
		public void onOrientationChanged(int orientation) {
		        //Log.d(TAG, "onOrientationChanged: " + orientation);
			if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
				return;
			}
			if(IS_NEED_CHECK_ORSENSOR){
				if(!mDetectScreenOr){
					return;
				}
				long currentTime = System.currentTimeMillis();
				Log.d(TAG, "check current time: " + currentTime);

				long mDurationTime = currentTime - mTimeLaunchRegistered;
				Log.d(TAG, "duration time: " +mDurationTime );

				if (mDurationTime < CHECK_TIME){
					Log.d(TAG, "time < " +  CHECK_TIME + "ms");
					return;
				}
				if (mCurrentOr ==true){
					return;
				}
			}

        		if (((orientation >= 0) && (orientation <= 45)) || (orientation > 315)) {
            			mDeviceOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        		} else if ((orientation > 45) && (orientation <= 135)) {
            			mDeviceOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        		} else if ((orientation > 135) && (orientation <= 225)) {
            			mDeviceOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
       			 } else if ((orientation > 225) && (orientation <= 315)) {
            			mDeviceOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
       			} else {
            			mDeviceOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        		}
			mCurrentOr =true;

			Log.d(TAG, "Orientation: " + orientation + " ,landscape or portrait mode: " +mDeviceOrientation);
		}
	}
    //e:Ethan Wu 20180131, launch camera if screen is on landscape mode.
}