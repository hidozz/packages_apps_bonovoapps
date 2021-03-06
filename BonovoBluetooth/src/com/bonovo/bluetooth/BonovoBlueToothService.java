package com.bonovo.bluetooth;

import android.app.KeyguardManager;
import android.app.KeyguardManager.KeyguardLock;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.widget.Toast;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.IBinder;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.content.ContentProviderOperation;
import android.content.OperationApplicationException;
import android.content.ContentUris;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.RemoteException;
import android.util.Log;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.media.RemoteControlClient.MetadataEditor;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.ArrayList;

import com.bonovo.bluetooth.BonovoBlueToothReceiver;

@SuppressWarnings("deprecation")
public class BonovoBlueToothService extends Service implements AudioManager.OnAudioFocusChangeListener {
	private static final String TAG = "BonovoBlueToothService";
	private boolean DEB = false;							// Debugging mode, on or off
	private static Context mContext;

	private boolean myBtSwitchStatus = true; 			// 0:BT power off; 1:BT power on
	private boolean myBtHFPStatus = false;  				// 0:Phone function disable; 1:Phone function enable
	private boolean myBtMusicStatus = false;				// true if A2DP is supposed to be playing, else false
	private boolean mBtMusicIsEnable = false;			// true if A2DP is enabled, else false
	private boolean mStartComplete = false;				// true when the BT module has completed it's startup process
	private boolean mHFPProfileConnected = false;		// True when the HFP profile is actually connected to a device
	private boolean mA2DPProfileConnected = false;		// True when the A2DP profile is actually connected to a device
	private boolean mReadName = false;
	private boolean mReadPinCode = false;
	private boolean mIsBindered = false;
	private boolean mIsSyncingContacts = false;
	private boolean mMicMuted = false;					// HFP is the microphone muted or not
	private int mPhoneSignalLevel = 0;					// Signal level of connected phone, between 0 and 5
	private int mPhoneBatteryLevel = 0;					// Battery level of connected phone, between 0 and 5
	private String mPhoneOperatorName = "";				// Name of the network the phone is connected to (if provided by phone)
	private int mSetNameTime = 0;
	private int mSetPinCodeTime = 0;
	public String a2dpTrackName = "Bluetooth Audio";		// A2DP track information
	public String a2dpArtist = "";						//   ""
    public String a2dpAlbum = "";						//   ""
    public long trackLenMs = 0;							// current playing track length (milliseconds)
    public long trackPosMs = 0;							// current playing position in track (milliseconds)
    private boolean mHasFailed = false;					// True if the BT module has reported an error, false for normal operation
    private boolean mA2DPPlaying = false; 				// Playback state as returned from the remote device
    private final static int MAX_SET_TIME = 5; 
	private final static String DEF_BT_NAME = "BTHFD";	// Default name
	private final static String DEF_BT_PIN = "1234";		// Default PIN
	private String myBtName = DEF_BT_NAME; 				// BT name
	private String myBtPinCode = DEF_BT_PIN; 			// PIN code
	private PhoneState mBtPhoneState = PhoneState.IDLE;	// State of the phone connected to the HFP profile 
    private List<Contact> mListContacts;
    private AudioManager mAudioManager;
    private RemoteControlClient mRemoteControlClient;
    private int mBTErrCount = 0;
    private boolean mBtMusicFocus = false;				// True if we want exclusive focus when playing. false wont take exclusive focus
	
	/**
	 * The Phone state. One of the following:
	 * IDLE = no phone activity
	 * RINGING = a phone call is ringing or call waiting.
	 * ACTIVE = a phone is answered.
	 * OFFHOOK = HFP disconnect.
	 */
	public enum PhoneState{
		IDLE, RINGING, DIALING, ACTIVE, OFFHOOK;
	}
	
    class AudioLevel {
        public static final int CODEC_LEVEL_NO_ANALOG = 0;
        public static final int CODEC_LEVEL_BT_MUSIC = 1;
        public static final int CODEC_LEVEL_AV_IN = 2;
        public static final int CODEC_LEVEL_DVB = 3;
        public static final int CODEC_LEVEL_DVD = 4;
        public static final int CODEC_LEVEL_RADIO = 5;
        public static final int CODEC_LEVEL_BT_TEL = 6;
	}

	private static final int MSG_START_HANDFREE = 0;
	private static final int MSG_PHONE_STATE_CHANGE = 1;
	private static final int MSG_HFP_STATE_CHANGE = 2;
	private static final int MSG_AUDIO_STATE_CHANGE = 3;
	private static final int MSG_BT_NAME_INFO = 4;
	private static final int MSG_BT_PINCODE_INFO = 5;
	private static final int MSG_BT_SHUTDOWN = 6;
	private static final int MSG_BT_HFP_DISCONNECT = 7;
	private static final int MSG_BT_A2DP_DISCONNECT = 8;
	private static final int MSG_BT_CHECK_NAME = 9;
	private static final int MSG_BT_CHECK_PINCODE = 10;
    private static final int MSG_BT_SHOW_INFO = 11;
    private static final int MSG_SYNC_PHONE_BOOK_COMPLETE = 12;
    private static final int MSG_SYNC_CONTACTS_READ_COUNT = 13;
    private static final int MSG_SYNC_CONTACTS_WRITE_DATABASE = 14;
    private static final int MSG_SYNC_CONTACTS_TIMEOUT = 15;
    private static final int MSG_SYNC_CONTACTS_NOTSUPPORT = 16;
    private static final int MSG_ACTIVE_AUDIO = 20;
    private static final int MSG_RECOVERY_AUDIO = 21;
    private static final int MSG_STOP_MUSIC = 22;
    private static final int MSG_BT_MIC_STATE_CHANGE = 23;
    private static final int MSG_SEND_COMMANDER_ERROR = 30;
    private static final int MSG_PHONE_NETWORKNAME = 32;
    private static final int MSG_PHONE_BATTERYLEVEL = 33;
    private static final int MSG_PHONE_SIGNALLEVEL = 34;
    private static final int MSG_PHONE_NEW_CALL_WAITING = 35;
    private static final int MSG_PHONE_HELD_ACTIVE_SWITCHED_TO_CALL_WAITING = 36;
    private static final int MSG_PHONE_CONFERENCE_CALL = 37;
    private static final int MSG_PHONE_HUNG_UP_INACTIVE = 38;
    private static final int MSG_PHONE_HUNG_UP_ACTIVE_SWITCHED_TO_CALL_WAITING = 39;
    private static final int MSG_UPDATE_A2DP_TRACKINFO = 40;
	private static final int DELAY_TIME_CHECKPINCODE = 2000;
	private static final int DELAY_TIME_DISCONNECT = 1000;
	private static final int DELAY_TIME_SHUTDOWN = 5000;
    private static final int DELAY_TIME_STOP_MUSIC = 5 * 1000;
    private static final int DELAY_TIMEOUT_SYNC_CONTACTS = 60 * 1000;
    private static final int DELAY_TIMEOUT_WAIT_USER = 30 * 1000;
    private static final int MAX_COUNT_CONTACTS_PRE_SYNC = 500;
    private static final int MAX_PAUSE_MUSIC_TIMES = 5;
    private int mMusicStopTimes = 0;
	private String mCurrNumber = "";
	private long mAnswerTimer = -1;
	private boolean mIsBtWillShutDown = false;
	private boolean mHasAudioFocus = false;		// True if this service has the system's audio focus, used for A2DP
	private final static String ACTION_BT_POWERON = "android.intent.action.BONOVO_BT_POWERON";
	private final static String ACTION_BT_POWEROFF = "android.intent.action.BONOVO_BT_POWEROFF";
	
	// Phone actions
	private final static String ACTION_CALL_DIAL = "android.intent.action.BONOVO_CALL_DIAL";
	private final static String ACTION_CALL_ANSWER = "android.intent.action.BONOVO_CALL_ANSWER";
	private final static String ACTION_CALL_HANGUP = "android.intent.action.BONOVO_CALL_HANGUP";
	private final static String ACTION_CALL_MUTE = "android.intent.action.BONOVO_CALL_MUTE";
	private final static String ACTION_CALL_SWITCHAUDIO = "android.intent.action.BONOVO_CALL_SWITCHAUDIO";
	private final static String ACTION_CALL_VOLUMEUP = "android.intent.action.BONOVO_CALL_VOLUMEUP";
	private final static String ACTION_CALL_VOLUMEDOWN = "android.intent.action.BONOVO_CALL_VOLUMEDOWN";
	private final static String ACTION_CALL_REJECTCALLWAITING = "android.intent.action.BONOVO_CALL_REJECTCALLWAITING";
	private final static String ACTION_CALL_ENDANDACCEPTCALLWAITING = "android.intent.action.BONOVO_CALL_ENDANDACCEPTCALLWAITING";
	private final static String ACTION_CALL_HOLDANDACCEPTCALLWAITING = "android.intent.action.BONOVO_CALL_HOLDANDACCEPTCALLWAITING";
	private final static String ACTION_CALL_MAKECONFERENCECALL = "android.intent.action.BONOVO_CALL_MAKECONFERENCECALL";
	private final static String ACTION_CALL_VOICEDIAL = "android.intent.action.BONOVO_CALL_VOICEDIAL";
	private final static String ACTION_CALL_VOICEDIAL_CANCEL = "android.intent.action.BONOVO_CALL_VOICEDIAL_CANCEL";
	
	// A2DP actions
	private final static String ACTION_MUSIC_PLAY = "android.intent.action.BONOVO_BTMUSIC_PLAY";
	private final static String ACTION_MUSIC_STOP = "android.intent.action.BONOVO_BTMUSIC_STOP";
	private final static String ACTION_MUSIC_PAUSE = "android.intent.action.BONOVO_BTMUSIC_PAUSE";
	private final static String ACTION_MUSIC_PREVTRACK = "android.intent.action.BONOVO_BTMUSIC_PREVTRACK";
	private final static String ACTION_MUSIC_NEXTTRACK = "android.intent.action.BONOVO_BTMUSIC_NEXTTRACK";
	private final static String ACTION_MUSIC_INFO_REFRESH = "android.intent.action.BONOVO_REQUEST_TRACKINFO_REFRESH";
	
    // onKeyEvent
    private final static String ACTION_KEY_BT = "android.intent.action.BONOVO_BT";
    private final static String ACTION_KEY_BT_ANSWER = "android.intent.action.BONOVO_BT_ANSWER";
    private final static String ACTION_KEY_BT_HANG_UP = "android.intent.action.BONOVO_BT_HANG_UP";
    private final static String ACTION_KEY_BT_ANSWER_HANG = "android.intent.action.BONOVO_BT_ANSWER_HANG";
    private final static String ACTION_KEY_BT_SWITCH_AUDIO = "android.intent.action.BONOVO_BT_SWITCH_AUDIO";

	private native void BonovoBlueToothInit();

	private native void BonovoBlueToothDestroy();

	private native void BonovoBlueToothSet(int cmd);

	private native int BonovoBlueToothPower(int status);
	private native int BonovoBlueToothActiveAudio(int level);
	private native int BonovoBlueToothRecoveryAudio(int level);

	//private native String GetContacts();
	
	private native void BonovoBlueToothSetWithParam(int cmd, String param);
	
	private ServiceBinder serviceBinder = new ServiceBinder();

	public class ServiceBinder extends Binder {

		public BonovoBlueToothService getService() {
			mIsBindered = true;
			return BonovoBlueToothService.this;
		}
	}

	static {
		System.loadLibrary("bonovobluetooth");
	}
	
	@Override
	public void onRebind(Intent intent){
		mIsBindered = true;
		super.onRebind(intent);
	}
	
	@Override
	public boolean onUnbind(Intent intent){
		mIsBindered = false;
		return super.onUnbind(intent);
	}
	
	private IntentFilter getIntentFilter() {
		IntentFilter myIntentFilter = new IntentFilter();
        myIntentFilter.addAction("android.intent.action.ACTION_SHUTDOWN");
		myIntentFilter.addAction("android.intent.action.BONOVO_SLEEP_KEY");
		myIntentFilter.addAction("android.intent.action.BONOVO_WAKEUP_KEY");
		myIntentFilter.addAction("BlueTooth.Media_Broadcast_Next");
        myIntentFilter.addAction("BlueTooth.Media_Broadcast_Last");
        myIntentFilter.addAction("BlueTooth.Media_Broadcast_Play_Pause");
		myIntentFilter.addAction(ACTION_CALL_DIAL);
		myIntentFilter.addAction(ACTION_CALL_ANSWER);
		myIntentFilter.addAction(ACTION_CALL_HANGUP);
		myIntentFilter.addAction(ACTION_CALL_MUTE);
		myIntentFilter.addAction(ACTION_CALL_SWITCHAUDIO);
		myIntentFilter.addAction(ACTION_CALL_VOLUMEUP);
		myIntentFilter.addAction(ACTION_CALL_VOLUMEDOWN);
		myIntentFilter.addAction(ACTION_KEY_BT);
		myIntentFilter.addAction(ACTION_KEY_BT_ANSWER);
		myIntentFilter.addAction(ACTION_KEY_BT_HANG_UP);
		myIntentFilter.addAction(ACTION_KEY_BT_ANSWER_HANG);
		myIntentFilter.addAction(ACTION_KEY_BT_SWITCH_AUDIO);
		myIntentFilter.addAction(ACTION_CALL_REJECTCALLWAITING);
		myIntentFilter.addAction(ACTION_CALL_ENDANDACCEPTCALLWAITING);
		myIntentFilter.addAction(ACTION_CALL_HOLDANDACCEPTCALLWAITING);
		myIntentFilter.addAction(ACTION_CALL_MAKECONFERENCECALL);
		myIntentFilter.addAction(ACTION_CALL_VOICEDIAL);
		myIntentFilter.addAction(ACTION_CALL_VOICEDIAL_CANCEL);
		myIntentFilter.addAction(ACTION_MUSIC_PLAY);
		myIntentFilter.addAction(ACTION_MUSIC_PAUSE);
		myIntentFilter.addAction(ACTION_MUSIC_STOP);
		myIntentFilter.addAction(ACTION_MUSIC_NEXTTRACK);
		myIntentFilter.addAction(ACTION_MUSIC_PREVTRACK);
		myIntentFilter.addAction(ACTION_MUSIC_INFO_REFRESH);
		return myIntentFilter;
	};
	
	private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver(){

		@Override
		public void onReceive(Context context, Intent intent) {
			// TODO Auto-generated method stub
			String action = intent.getAction();
			if(DEB) Log.d(TAG, "====== action:" + action 
               + " BroadcastReceiver myBtSwitchStatus = " + myBtSwitchStatus);
			if(action.equals("android.intent.action.BONOVO_SLEEP_KEY")
               || action.equals("android.intent.action.ACTION_SHUTDOWN")){
				if(myBtSwitchStatus){
					BlueToothMusicStop(); 
					trackLenMs = 0;		// hack to force the track info to reload on wakeup
					myBtSwitchStatus = false;
					mIsBtWillShutDown = true;
	                mIsBindered = false;
                    mStartComplete = false;
					mHandler.sendEmptyMessage(MSG_BT_HFP_DISCONNECT);
				}
			}else if(action.equals("android.intent.action.BONOVO_WAKEUP_KEY")){
				mHandler.removeMessages(MSG_BT_SHUTDOWN);
				mHandler.removeMessages(MSG_BT_A2DP_DISCONNECT);
				mHandler.removeMessages(MSG_BT_HFP_DISCONNECT);
				mIsBtWillShutDown = false;
				SharedPreferences settings = getSharedPreferences("com.bonovo.bluetooth", MODE_PRIVATE);
				myBtSwitchStatus = settings.getBoolean("myBtSwitchStatus", false);
				if (!myBtSwitchStatus) {
					setBtHFPStatus(false);
					myBtMusicStatus = false;
				}else{
				    setBtSwitchStatus(myBtSwitchStatus);
				    //BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_CC);
				}
			}else if(action.equals(ACTION_CALL_DIAL)){
			    if(getBtHFPStatus()){
				    String number = intent.getStringExtra(BonovoBlueToothData.PHONE_NUMBER);
				    BlueToothPhoneDial(number);
			    }else{
			        Log.e(TAG, "HFP Not Connect!  intent:" + action);
                    showToast(getString(R.string.description_phone_disable));
			    }
			}else if(action.equals(ACTION_CALL_ANSWER)){
			    if(getPhoneState() == PhoneState.RINGING){
				    BlueToothPhoneAnswer();
			    }
			}else if(action.equals(ACTION_CALL_HANGUP)){
			    PhoneState status = getPhoneState();
				if(status == PhoneState.RINGING){
					BlueToothPhoneRejectCall();
				}else if((status == PhoneState.DIALING) || (status == PhoneState.ACTIVE)){
					BlueToothPhoneHangup();
				}
			}else if(action.equals(ACTION_CALL_MUTE)){
			    if(getBtHFPStatus()){
				    BlueToothPhoneMute();
			    }else{
			        Log.e(TAG, "HFP Not Connect!  intent:" + action);
                    showToast(getString(R.string.description_phone_disable));
			    }
			}else if(action.equals(ACTION_CALL_SWITCHAUDIO)
			    || action.equals(ACTION_KEY_BT_SWITCH_AUDIO)){
			    if(getBtHFPStatus()){
				    BlueToothSwitchAudio();
			    }else{
			        Log.e(TAG, "HFP Not Connect!  intent:" + action);
                    showToast(getString(R.string.description_phone_disable));
			    }
			}else if(action.equals(ACTION_CALL_VOLUMEUP)){
			    if(getBtHFPStatus()){
				    BlueToothPhoneVolumeUp();
			    }else{
			        Log.e(TAG, "HFP Not Connect!  intent:" + action);
                    showToast(getString(R.string.description_phone_disable));
			    }
			}else if(action.equals(ACTION_CALL_VOLUMEDOWN)){
                if(getBtHFPStatus()){
                    BlueToothPhoneVolumeDown();
                }else{
                    Log.e(TAG, "HFP Not Connect!  intent:" + action);
                    showToast(getString(R.string.description_phone_disable));
                }
			}else if(action.equals(ACTION_BT_POWERON)){
				setBtSwitchStatus(true);
			}else if(action.equals(ACTION_BT_POWEROFF)){
				setBtSwitchStatus(false);
			}else if(action.equals(ACTION_KEY_BT)){
			    if(getBtHFPStatus()){
                    Message msg = mHandler.obtainMessage(MSG_START_HANDFREE);
                    mHandler.sendMessage(msg);
			    }else{
			        Log.e(TAG, "HFP Not Connect!  intent:" + action);
			        showToast(getString(R.string.description_phone_disable));
			    }
			}else if(action.equals(ACTION_KEY_BT_ANSWER)){
			    if(getPhoneState() == PhoneState.RINGING){
                    BlueToothPhoneAnswer();
			    }
			}else if(action.equals(ACTION_KEY_BT_HANG_UP)){
			    if(getPhoneState() == PhoneState.RINGING){
                    BlueToothPhoneRejectCall();
			    }else if((getPhoneState() == PhoneState.DIALING)
                    || (getPhoneState() == PhoneState.ACTIVE)){
                    BlueToothPhoneHangup();
			    }
			}else if(action.equals(ACTION_KEY_BT_ANSWER_HANG)){
			    if(getPhoneState() == PhoneState.RINGING){
                    BlueToothPhoneAnswer();
			    }else if((getPhoneState() == PhoneState.DIALING)
                    || (getPhoneState() == PhoneState.ACTIVE)){
                    BlueToothPhoneHangup();
			    }
			}else if(action.equals(ACTION_CALL_MAKECONFERENCECALL)){
			    if(getPhoneState() == PhoneState.ACTIVE){
				    BlueToothPhoneConferenceCalls();
			    }
			}else if(action.equals(ACTION_CALL_HOLDANDACCEPTCALLWAITING)){
			    if(getPhoneState() == PhoneState.ACTIVE){
				    BlueToothPhoneHoldAndSwitchToWaitingCall();
			    }
			}else if(action.equals(ACTION_CALL_ENDANDACCEPTCALLWAITING)){
			    if(getPhoneState() == PhoneState.ACTIVE){
				    BlueToothPhoneEndAndSwitchToWaitingCall();
			    }
			}else if(action.equals(ACTION_CALL_REJECTCALLWAITING)){
			    if(getPhoneState() == PhoneState.ACTIVE){
				    BlueToothPhoneRejectWaitingCall();
			    }
			}else if(action.equals(ACTION_CALL_VOICEDIAL)){
				BlueToothPhoneVoiceDial();
			}else if(action.equals(ACTION_CALL_VOICEDIAL_CANCEL)){
				BlueToothPhoneVoiceDialCancel();
			}else if(action.equals(ACTION_MUSIC_PLAY)){
				BlueToothMusicPlay();
			}else if(action.equals(ACTION_MUSIC_STOP)){
				BlueToothMusicStop();
			}else if(action.equals(ACTION_MUSIC_PAUSE) || action.equals("BlueTooth.Media_Broadcast_Play_Pause")){
				BlueToothMusicPause();
			}else if(action.equals(ACTION_MUSIC_NEXTTRACK) || action.equals("BlueTooth.Media_Broadcast_Next")){
				BlueToothMusicNext();
			}else if(action.equals(ACTION_MUSIC_PREVTRACK) || action.equals("BlueTooth.Media_Broadcast_Last")){
				BlueToothMusicPre();
			}else if(action.equals(ACTION_MUSIC_INFO_REFRESH)){
				// Force a track info refresh 
				if(mA2DPProfileConnected) {
					BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_QE);
				}
			}
        }
	};
	
	private void wakeUpAndUnlockIfNeed(){
		PowerManager mPm=(PowerManager) getSystemService(Context.POWER_SERVICE);
		if(!mPm.isScreenOn()){
			PowerManager.WakeLock mWl = mPm.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "BonovoBt");
			mWl.acquire();
			mWl.release();
		}
		
		KeyguardManager mKm = (KeyguardManager)getSystemService(Context.KEYGUARD_SERVICE);
		if(mKm.inKeyguardRestrictedInputMode()){
			KeyguardLock mKl = mKm.newKeyguardLock("unLock");
			mKl.disableKeyguard();
		}
	}

    private void showToast(final String info){
        Message msg = mHandler.obtainMessage(MSG_BT_SHOW_INFO, info);
        mHandler.sendMessage(msg);
    }
	
	private Handler mHandler = new Handler() {
		@SuppressWarnings("deprecation")
		public void handleMessage(Message msg) {
			int what = msg.what;
			switch (what) {
			case MSG_START_HANDFREE:{
				wakeUpAndUnlockIfNeed();
				//Intent intent = new Intent(BonovoBlueToothService.this, BonovoBluetoothHandfree.class);
				//intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				Intent intent = new Intent(Intent.ACTION_MAIN, null);
				intent.addCategory("android.intent.category.APP_BT_PHONE");
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
				intent.putExtra(BonovoBlueToothData.PHONE_NUMBER, (String)msg.obj);
				startActivity(intent);

				Intent i2 = new Intent(BonovoBlueToothData.ACTION_PHONE_STATE_CHANGED);
				i2.putExtra(BonovoBlueToothData.PHONE_STATE, getPhoneState().toString());
				mContext.sendOrderedBroadcast(i2, null);
				break;
			}
			case MSG_PHONE_STATE_CHANGE:{
				Intent i = new Intent(BonovoBlueToothData.ACTION_PHONE_STATE_CHANGED);
				if(msg.obj != null){
//					mCurrNumber = (String)msg.obj;
					setCurrentNumber((String)msg.obj);
					i.putExtra(BonovoBlueToothData.PHONE_NUMBER, (String)msg.obj);
				}
				i.putExtra(BonovoBlueToothData.PHONE_STATE, getPhoneState().toString());
				
				mContext.sendOrderedBroadcast(i, null);
			}
			break;
			case MSG_PHONE_NEW_CALL_WAITING:{
				Intent icw = new Intent(BonovoBlueToothData.ACTION_PHONE_NEW_CALL_WAITING);
				icw.putExtra(BonovoBlueToothData.PHONE_NUMBER, (String)msg.obj);
				mContext.sendBroadcast(icw);
			}
			break;
			case MSG_PHONE_HELD_ACTIVE_SWITCHED_TO_CALL_WAITING:{
				Intent icw = new Intent(BonovoBlueToothData.ACTION_PHONE_HELD_ACTIVE_SWITCHED_TO_CALL_WAITING);
				mContext.sendBroadcast(icw);
			}
			break;
			case MSG_PHONE_CONFERENCE_CALL:{
				Intent icw = new Intent(BonovoBlueToothData.ACTION_PHONE_CONFERENCE_CALL);
				mContext.sendBroadcast(icw);
			}
			break;
			case MSG_PHONE_HUNG_UP_INACTIVE:{
				Intent icw = new Intent(BonovoBlueToothData.ACTION_PHONE_HUNG_UP_INACTIVE);
				mContext.sendBroadcast(icw);
			}
			break;
			case MSG_PHONE_HUNG_UP_ACTIVE_SWITCHED_TO_CALL_WAITING:{
				Intent icw = new Intent(BonovoBlueToothData.ACTION_PHONE_HUNG_UP_ACTIVE_SWITCHED_TO_CALL_WAITING);
				mContext.sendBroadcast(icw);
			}
			break;
	
			case MSG_PHONE_SIGNALLEVEL:{
				Integer newLevel = (Integer)msg.obj;
				if(!newLevel.equals(mPhoneSignalLevel)) {
					mPhoneSignalLevel = newLevel;
					Intent icw = new Intent(BonovoBlueToothData.ACTION_PHONE_SIGNAL_LEVEL_CHANGED);
					icw.putExtra(BonovoBlueToothData.LEVEL, newLevel);
					mContext.sendBroadcast(icw);
				}
			}
			break;
			case MSG_PHONE_BATTERYLEVEL:{
				Integer newLevel = (Integer)msg.obj;
				if(!newLevel.equals(mPhoneBatteryLevel)) {
					mPhoneBatteryLevel = newLevel;
					Intent icw = new Intent(BonovoBlueToothData.ACTION_PHONE_BATTERY_LEVEL_CHANGED);
					icw.putExtra(BonovoBlueToothData.LEVEL, newLevel);
					mContext.sendBroadcast(icw);
				}
			}
			break;
			case MSG_PHONE_NETWORKNAME:{
				String newName = (String)msg.obj;
				if(!newName.equals(mPhoneOperatorName)) {
					mPhoneOperatorName = newName;
					Intent icw = new Intent(BonovoBlueToothData.ACTION_PHONE_NETWORK_NAME_CHANGED);
					icw.putExtra(BonovoBlueToothData.NAME, newName);
					mContext.sendBroadcast(icw);
				}
			}
			break;
			case MSG_HFP_STATE_CHANGE:{
//				Bundle bundle = new Bundle();
				Intent intent1 = new Intent(BonovoBlueToothData.ACTION_DATA_IAIB_CHANGED);
				intent1.putExtra(BonovoBlueToothData.HFP_STATUS, getBtHFPStatus());
//				intent1.putExtras(bundle);
				mContext.sendBroadcast(intent1);
			}
			break;
			case MSG_AUDIO_STATE_CHANGE:{
//				Bundle bundle = new Bundle();
				Intent intent3 = new Intent(BonovoBlueToothData.ACTION_DATA_MAMB_CHANGED);
				intent3.putExtra(BonovoBlueToothData.A2DP_STATUS, getMusicStatus());
//				intent3.putExtras(bundle);
				mContext.sendBroadcast(intent3);
			}
			break;
			case MSG_BT_NAME_INFO:{
				if(mStartComplete && mReadName){
					if(mIsBindered){
                        mSetNameTime = 0;
                        mReadName = false;
						Intent i = new Intent(BonovoBlueToothData.ACTION_BT_NAME);
						if(msg.obj != null){
							i.putExtra(BonovoBlueToothData.ACTION_INFO_BT_NAME, cleanInfo((String)msg.obj));
						}
						mContext.sendBroadcast(i);

					}else{
						if(!getBtName().equals(cleanInfo((String)msg.obj))){
                            mSetNameTime = mSetNameTime + 1;
                            if(mSetNameTime > MAX_SET_TIME){
                                mSetNameTime = 0;
                                mReadName = false;
                            }else{
							    BlueToothSetOrCheckName(getBtName());
                            }
						}else{
						    mSetNameTime = 0;
                            mReadName = false;
						}
					}
				}
			}
			break;
			case MSG_BT_PINCODE_INFO:{
				if(mStartComplete && mReadPinCode){
					if(mIsBindered){
                        mSetPinCodeTime = 0;
                        mReadPinCode = false;
						Intent i = new Intent(BonovoBlueToothData.ACTION_BT_PINCODE);
						if(msg.obj != null){
							i.putExtra(BonovoBlueToothData.ACTION_INFO_BT_PINCODE, cleanInfo((String)msg.obj));
						}
						mContext.sendBroadcast(i);
					}else{
						if(!getBtPinCode().equals(cleanInfo((String)msg.obj))){
                            mSetPinCodeTime = mSetPinCodeTime + 1;
                            if(mSetPinCodeTime > MAX_SET_TIME){
                                mSetPinCodeTime = 0;
                                mReadPinCode = false;
                            }else{
                                BlueToothSetOrCheckPin(getBtPinCode());
                            }
						}else{
						    mSetPinCodeTime = 0;
                            mReadPinCode = false;
						}
					}
				}
			}
			break;
			case MSG_BT_SHUTDOWN:{
				if(DEB) Log.d(TAG, "====== Handler  shutdown 111");
				PhoneState tempPhoneStatus = getPhoneState();
				if(tempPhoneStatus != PhoneState.OFFHOOK && tempPhoneStatus != PhoneState.IDLE){
					setBtHFPStatus(false);
				}
                if(getMusicStatus()){
                    setMusicStatus(false);
                }
	            mStartComplete = false;
				BonovoBlueToothPower(0);
			}
				break;
			case MSG_BT_HFP_DISCONNECT:{
				BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_CD);
				this.sendEmptyMessageDelayed(MSG_BT_A2DP_DISCONNECT, DELAY_TIME_DISCONNECT);
			}
				break;
			case MSG_BT_A2DP_DISCONNECT:{
				mHandler.removeMessages(MSG_UPDATE_A2DP_TRACKINFO);
				BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_DA);
				this.sendEmptyMessageDelayed(MSG_BT_SHUTDOWN, DELAY_TIME_SHUTDOWN);
			}
				break;
			case MSG_BT_CHECK_NAME:{
				BlueToothSetOrCheckName(null);
			}
				break;
			case MSG_BT_CHECK_PINCODE:{
				BlueToothSetOrCheckPin(null);
			}
				break;
            case MSG_BT_SHOW_INFO:
                String info = (String)msg.obj;
                if(info != null)
                    Toast.makeText(mContext, info, Toast.LENGTH_SHORT).show();
                break;
            case MSG_SYNC_PHONE_BOOK_COMPLETE:
                break;
            case MSG_SYNC_CONTACTS_READ_COUNT:{
                int count = msg.arg1;
                if(count > 0){
                    this.removeMessages(MSG_SYNC_CONTACTS_TIMEOUT);
                    this.sendEmptyMessageDelayed(MSG_SYNC_CONTACTS_TIMEOUT, DELAY_TIMEOUT_SYNC_CONTACTS);
                    Intent i = new Intent(BonovoBlueToothData.ACTION_SYNC_CONTACTS_READ_COUNT);
				    i.putExtra(BonovoBlueToothData.KEY_SYNC_CONTACTS_COUNT, count);
				    mContext.sendBroadcast(i);
                }
                break;
            }
            case MSG_SYNC_CONTACTS_WRITE_DATABASE:{
                if(mContext == null || mListContacts == null){
                    mIsSyncingContacts = false;
                    break;
                }
                new Thread() {
    				@Override
    				public void run() {
    				    mHandler.removeMessages(MSG_SYNC_CONTACTS_TIMEOUT);
                        if(mListContacts.size() > 0){
                            Intent i = new Intent(BonovoBlueToothData.ACTION_SYNC_CONTACTS_WRITE_DATABASE);
        				    i.putExtra(BonovoBlueToothData.KEY_SYNC_CONTACTS_COUNT, mListContacts.size());
        				    mContext.sendBroadcast(i);
                            //AddContactsNoDuplicateNames(mContext, mListContacts);
        					AddContactsInDataBase(mContext, mListContacts);
                            mListContacts.clear();
                        }
                        mIsSyncingContacts = false;
            			Intent intent = new Intent(BonovoBlueToothData.ACTION_SYNC_CONTACTS_COMPLETE);
            			sendBroadcast(intent);
    				}
    			}.start();
                break;
            }
            case MSG_STOP_MUSIC:{
                if((mMusicStopTimes < MAX_PAUSE_MUSIC_TIMES) 
                    && !getMusicServiceEnable() && getMusicStatus()){
                    //BlueToothMusicPause();
                    BlueToothMusicStop();
                    mMusicStopTimes++;
                    this.sendEmptyMessageDelayed(MSG_STOP_MUSIC, DELAY_TIME_STOP_MUSIC);
                }
                break;
            }
            case MSG_ACTIVE_AUDIO:{
                int level = msg.arg1;
                if((level == AudioLevel.CODEC_LEVEL_BT_MUSIC)
        		    && !getMusicServiceEnable()){
        			break;
        		}else{
        			BonovoBlueToothActiveAudio(level);
        		}
                break;
            }
            case MSG_RECOVERY_AUDIO:{
                int level = msg.arg1;
                BonovoBlueToothRecoveryAudio(level);
        		break;
            }
            case MSG_SYNC_CONTACTS_TIMEOUT:{
                if(mContext == null || mListContacts == null){
                    mIsSyncingContacts = false;
                    break;
                }

                new Thread() {
    				@Override
    				public void run() {
                        if(mListContacts.size() > 0){
                            Intent i = new Intent(BonovoBlueToothData.ACTION_SYNC_CONTACTS_TIMEOUT);
        				    i.putExtra(BonovoBlueToothData.KEY_SYNC_CONTACTS_COUNT, mListContacts.size());
        				    mContext.sendBroadcast(i);
                            //AddContactsNoDuplicateNames(mContext, mListContacts);
        					AddContactsInDataBase(mContext, mListContacts);
                            mListContacts.clear();
                        }
                        mIsSyncingContacts = false;
            			Intent intent = new Intent(BonovoBlueToothData.ACTION_SYNC_CONTACTS_COMPLETE);
            			sendBroadcast(intent);
    				}
    			}.start();
                break;
            }
            case MSG_SYNC_CONTACTS_NOTSUPPORT:{
                mIsSyncingContacts = false;
                Intent intent = new Intent(BonovoBlueToothData.ACTION_SYNC_CONTACTS_NOT_SUPPORT);
                sendBroadcast(intent);
                break;
            }
            case MSG_SEND_COMMANDER_ERROR:{
            	String errCode = (String)msg.obj;
            	if(errCode == "0003"){
            		showToast("HFP - serial command timeout");
            	}else if(errCode == "0001"){
            		showToast("HFP - event failed");
            	}else if(errCode == "0004"){
            		showToast("HFP - connection to phone failed");
            	}else if(errCode == "000B"){
            		showToast("HFP - pairing request failed");
            	}else if(errCode == "0010"){
            		showToast("A2DP - stop command failed");
            	}else if(errCode == "0020"){
            		showToast("A2DP - skip forward command failed");
            	}else if(errCode == "0030"){
            		showToast("A2DP - skip backward command failed");
            	}else if(errCode == "0080"){
            		showToast("A2DP - play / pause command failed");
            	}else if(errCode == "0100"){
            		showToast("Bluetooth - Remote device has not acknowledged sent command");
            	}else if(errCode == "0000"){
            		// Error code 0000 is too generic and seems to be alarming people unnecessarily
            		//  So lets not show it
            	} else {
            		showToast("Bluetooth - Error code: " + (String)msg.obj);
            	}
            	
                Intent intent = new Intent(BonovoBlueToothData.ACTION_SEND_COMMANDER_ERROR);
                intent.putExtra("ErrorCode", (String)msg.obj);
                sendBroadcast(intent);
                break;
            }
            case MSG_UPDATE_A2DP_TRACKINFO:{
            	if (mStartComplete && mA2DPProfileConnected){
	            	if(DEB) Log.d(TAG, " --> Updating A2DP track info.");
	            	
	    			// Request Bluetooth track current position and length
	            	BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_QD);
	            	
	    			// Run this again in a few seconds to update the info
	                mHandler.sendEmptyMessageDelayed(MSG_UPDATE_A2DP_TRACKINFO, 2000);
	                
            	} else {
            		if(DEB) Log.d(TAG, " --> A2DP track info update request ignored as BT module startup not complete or profile disconnected.");
            	}
            	break;
            }
			default:
				break;
			}
		}
	};

	@Override
	public void onCreate() {
		super.onCreate();
		mContext = getApplicationContext();
        mListContacts = new ArrayList<BonovoBlueToothService.Contact>();
        mListContacts.clear();
        
		getBtName();
		getBtPinCode();
		BonovoBlueToothInit();
//		BonovoBlueToothPower(0);
		SharedPreferences settings = getSharedPreferences("com.bonovo.bluetooth", MODE_PRIVATE);
		myBtSwitchStatus = settings.getBoolean("myBtSwitchStatus", false);
		if(DEB) Log.d(TAG, "++++++++ onCreate myBtSwitchStatus = " + myBtSwitchStatus);
		mBtMusicIsEnable = settings.getBoolean("mBtMusicIsEnable", false);
		if (!myBtSwitchStatus) {
			setBtHFPStatus(false);
			myBtMusicStatus = false;
		}
		mBtMusicFocus = settings.getBoolean("mBtMusicFocus", true);
		setBtSwitchStatus(myBtSwitchStatus);
		registerReceiver(mBroadcastReceiver, getIntentFilter());
	}

	@Override
	public IBinder onBind(Intent arg0) {
		// TODO Auto-generated method stub
		return serviceBinder;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		BonovoBlueToothDestroy();
		unregisterReceiver(mBroadcastReceiver);
	}

	public int recoveryAudio(int level){
        Message msg = mHandler.obtainMessage(MSG_RECOVERY_AUDIO, level, 0);
        mHandler.sendMessage(msg);
        return 0;
	}
	
	public int activeAudio(int level){
		if (level == AudioLevel.CODEC_LEVEL_BT_MUSIC && mBtMusicIsEnable == false) {
			// Abort if there is an attempt to activate music when it is switched off
			Log.d(TAG, "Attempt to activate BT music audio when it is disabled.");
			return 0;
		}
		Message msg = mHandler.obtainMessage(MSG_ACTIVE_AUDIO, level, 0);
		mHandler.sendMessage(msg);
        return 0;
	}
	
	//
	public void BlueToothSetCmd(int cmd) {
		BonovoBlueToothSet(cmd);
	}
	
	// build pbap link
	public void BlueToothBuildPbapContacts() {
		BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_QA);
	}
	
	// down load phone numbers through pbap
	public void BlueToothPbapContacts() {
		BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_QB);
	}
	
	// sync contacts in phone
	public void SynchPhoneContacts() {
	    if(mIsSyncingContacts){
            return;
        }
        mIsSyncingContacts = true;
	    mListContacts.clear();
		BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_PB);
        mHandler.sendEmptyMessageDelayed(MSG_SYNC_CONTACTS_TIMEOUT, DELAY_TIMEOUT_WAIT_USER);
	}
	
	// sync contacts in sim card
	public void SynchSimContacts() {
	    if(mIsSyncingContacts){
            return;
        }
        mIsSyncingContacts = true;
	    mListContacts.clear();
		BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_PA);
        mHandler.sendEmptyMessageDelayed(MSG_SYNC_CONTACTS_TIMEOUT, DELAY_TIMEOUT_WAIT_USER);
	}
	
	// read all records
	public void BlueToothDownloadContacts() {
		BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_PF);
	}
	
	// reset bt module
	public void BlueToothReset() {
		mStartComplete = false;
		mHasFailed = true;
		BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_CZ);
	}
	
	// read pairing list
	public void BlueToothHisRecord() {
		BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_MX);
	}
	
	// clear pairing records 
	public void BlueToothClear() {
		BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_CV);
	}
	
	// Music play
	public void BlueToothMusicPlay() {
		if (mA2DPProfileConnected) {
			BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_MA);
		}
	}

	// Music pause
	public void BlueToothMusicPause() {
		if (mA2DPProfileConnected) {
			BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_MA);
		}
	}

	// music stop
	public void BlueToothMusicStop() {
		if (mA2DPProfileConnected) {
			BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_MC);
		}
	}

	// music previous
	public void BlueToothMusicPre() {
		if (mA2DPProfileConnected) {
			BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_ME);
		}
	}

	// music next
	public void BlueToothMusicNext() {
		if (mA2DPProfileConnected) {
			BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_MD);
		}
	}
	
	/**
	 * switch audio channel
	 */
	public void BlueToothSwitchAudio(){
		BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_CO);
	}

	/**
	 * add by bonovo zbiao for bluetooth phone
	 * @param number telephone no.
	 */
	public void BlueToothPhoneDial(String number) {
		BonovoBlueToothSetWithParam(BonovoBlueToothRequestCmd.CMD_SOLICATED_CW, number);
	}
	
	/**
	 * answer the phone
	 */
	public void BlueToothPhoneAnswer(){
		if (mHFPProfileConnected) {
			BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_CE);
		}
	}
	
	/**
	 * rejecting a call
	 */
	public void BlueToothPhoneRejectCall(){
		if (mHFPProfileConnected) {
			BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_CF);
		}
	}
	
	/**
	 * Hangup the phone
	 */
	public void BlueToothPhoneHangup(){
		if (mHFPProfileConnected) {
			BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_CG);
		}
	}
	
	/**
	 * HFP Volume up
	 */
	public void BlueToothPhoneVolumeUp(){
		BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_CK);
	}
	
	/**
	 * HFP Volume down
	 */
	public void BlueToothPhoneVolumeDown(){
		BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_CL);
	}
	
	/**
	 * HFP Mute
	 */
	public void BlueToothPhoneMute(){
		BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_CM);
	}
	
	// Returns the current mute state of the BT Mic
	public boolean BlueToothMicrophoneState() {
		return mMicMuted;
	}
	
	/**
	 * DTMF Dial
	 */
	public void BlueToothPhoneDTMF(String number){
		if (mHFPProfileConnected) {
			BonovoBlueToothSetWithParam(BonovoBlueToothRequestCmd.CMD_SOLICATED_CX, number);
		}
	}
	
	/**
	 * HFP Reject call waiting
	 */
	public void BlueToothPhoneRejectWaitingCall() {
		if (mHFPProfileConnected) {
			BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_CQ);
		}
	}

	/**
	 * HFP End current call and switch to call waiting
	 */
	public void BlueToothPhoneEndAndSwitchToWaitingCall() {
		if (mHFPProfileConnected) {
			BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_CR);
		}
	}
	
	/**
	 * HFP Hold current call and switch to call waiting
	 */
	public void BlueToothPhoneHoldAndSwitchToWaitingCall() {
		if (mHFPProfileConnected) {
			BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_CS);
		}
	}
	
	/**
	 * HFP Merge active and waiting calls into conference
	 */
	public void BlueToothPhoneConferenceCalls() {
		if (mHFPProfileConnected) {
			BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_CT);
		}
	}

	/**
	 * Start voice dial, activates voice commands on the connected phone (siri / ok google)
	 */
	public void BlueToothPhoneVoiceDial() {
		if (mHFPProfileConnected) {
			BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_CI);
		}
	}
		
	/**
	 * Cancel the voice dial command
	 */
	public void BlueToothPhoneVoiceDialCancel() {
		if (mHFPProfileConnected) {
			BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_CJ);
		}
	}
	
	/**
	 * Set or check bt's name
	 * @param name  if name is null,check bt's name. Otherwise set name.
	 */
	public void BlueToothSetOrCheckName(String name){
		if(name != null){
			BonovoBlueToothSetWithParam(BonovoBlueToothRequestCmd.CMD_SOLICATED_MM, name);
		}else{
		    mReadName = true;
			BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_MM);
		}
	}
	
	/**
	 * Set or check bt's paring code
	 * @param pin  paring code. if pin is null, check bt's pin.Otherwise set pin.
	 */
	public void BlueToothSetOrCheckPin(String pin){
		if(pin != null){
			BonovoBlueToothSetWithParam(BonovoBlueToothRequestCmd.CMD_SOLICATED_MN, pin);
		}else{
		    mReadPinCode = true;
			BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_MN);
		}
	}

	/*
	 * set mBtMusicIsEnable
	 */
	public void setMusicServiceEnable(boolean offOn){
		mBtMusicIsEnable = offOn;
		SharedPreferences settings = getSharedPreferences("com.bonovo.bluetooth", Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = settings.edit();
		editor.putBoolean("mBtMusicIsEnable", mBtMusicIsEnable);
		editor.commit();
        if(getMusicStatus()){
            if(mBtMusicIsEnable){
                activeAudio(AudioLevel.CODEC_LEVEL_BT_MUSIC);
            }else{
                recoveryAudio(AudioLevel.CODEC_LEVEL_BT_MUSIC);
                mMusicStopTimes = 0;
                mHandler.sendEmptyMessage(MSG_STOP_MUSIC);
            }
        }
	}

	/*
	 * get mBtMusicFocus
	 */
	public boolean getMusicFocus(){
		return mBtMusicFocus;
	}

	/*
	 * set mBtMusicFocus
	 */
	public void setMusicFocus(boolean offOn){
		mBtMusicFocus = offOn;
		SharedPreferences settings = getSharedPreferences("com.bonovo.bluetooth", Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = settings.edit();
		editor.putBoolean("mBtMusicFocus", mBtMusicFocus);
		editor.commit();
	}

	/*
	 * get mBtMusicIsEnable
	 */
	public boolean getMusicServiceEnable(){
		return mBtMusicIsEnable;
	}
	
	/*
	 * get bluetooth name
	 */
	public String getBtName(){
		SharedPreferences settings = getSharedPreferences("com.bonovo.bluetooth", MODE_PRIVATE);
		myBtName = settings.getString("myBtName", DEF_BT_NAME); // BTHF - Bluetooth Hand Free devices 
		return myBtName;
	}
	
	/*
	 * set bluetooth name
	 */
	public void setBtName(String name){
		myBtName = name;
		SharedPreferences settings = getSharedPreferences("com.bonovo.bluetooth", Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = settings.edit();
		editor.putString("myBtName", myBtName);
		editor.commit();
	}
	
	/*
	 * get bluetooth pin code
	 */
	public String getBtPinCode(){
		SharedPreferences settings = getSharedPreferences("com.bonovo.bluetooth", MODE_PRIVATE);
		myBtPinCode = settings.getString("myBtPin", DEF_BT_PIN); // BTHF - Bluetooth Hand Free devices 
		return myBtPinCode;
	}
	
	/*
	 * set bluetooth pin code
	 */
	public void setBtPinCod(String pin){
		myBtPinCode = pin;
		SharedPreferences settings = getSharedPreferences("com.bonovo.bluetooth", Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = settings.edit();
		editor.putString("myBtPin", myBtPinCode);
		editor.commit();
	}

	/**
	 *  BT Power On/Off
	 */
	public void setBtSwitchStatus(boolean enable) {
		myBtSwitchStatus = enable;
		SharedPreferences settings = getSharedPreferences("com.bonovo.bluetooth", Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = settings.edit();
		editor.putBoolean("myBtSwitchStatus", myBtSwitchStatus);
		editor.commit();
        mStartComplete = false;
		if(!enable){  // do someting before poweroff.
			setBtHFPStatus(enable);
			mHandler.sendEmptyMessage(MSG_BT_HFP_DISCONNECT);
			//BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_MC);
			//BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_CD);
			//BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_CA);
		}else{
		    mHandler.removeMessages(MSG_BT_SHUTDOWN);
            mHandler.removeMessages(MSG_BT_A2DP_DISCONNECT);
            mHandler.removeMessages(MSG_BT_HFP_DISCONNECT);
			BonovoBlueToothPower(1);
		}
		//if(enable){
		//	BlueToothSetOrCheckName(myBtName);
		//	BlueToothSetOrCheckPin(myBtPinCode);
		//}
	}

	public boolean getBtSwitchStatus() {
		return myBtSwitchStatus;
	}
	
	/**
	 * get Bt phone state
	 * @return
	 */
	public PhoneState getPhoneState(){
		return mBtPhoneState;
	}
	
	/**
	 * set Bt phone state
	 * @param state
	 */
	public void setPhoneState(PhoneState state){
		mBtPhoneState = state;
		if(mBtPhoneState == PhoneState.ACTIVE){
			setAnswerTime(SystemClock.elapsedRealtime());
		}else if(mBtPhoneState == PhoneState.IDLE || mBtPhoneState == PhoneState.OFFHOOK){
			clearAnswerTime();
		}
	}

	// set bt HFP status
	public void setBtHFPStatus(boolean enable) {
		myBtHFPStatus = enable;
		if(enable){
			setPhoneState(PhoneState.IDLE);
		}else{
			setPhoneState(PhoneState.OFFHOOK);
		}
	}

	public boolean getBtHFPStatus() {
		return myBtHFPStatus;
	}

	// set bt music status
	public void setMusicStatus(boolean enable) {
	    myBtMusicStatus = enable;
	}

	public boolean getMusicStatus() {
		return myBtMusicStatus;
	}
	
	public String getCurrentNumber() {
		return mCurrNumber;
	}
	
	private String cleanInfo(String info){
		byte[] temp = info.getBytes();
		int i = 0;
		for(i=0; i<info.length(); i++){
			if((temp[i] == '\0') || (temp[i] == '\r') || (temp[i] == '\n')){
				break;
			}
		}
		String subInfo = info.substring(0, i);
		return subInfo;
	}
	
	public void setCurrentNumber(String number) {
		mCurrNumber = cleanInfo(number);
	}
	
	public long getAnswerTime(){
		return mAnswerTimer;
	}
	
	public void clearAnswerTime(){
		mAnswerTimer = -1;
	}
	
	private void setAnswerTime(long time){
		mAnswerTimer = time;
	}

	private void getAudioFocus(){
		int result;
		mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
		Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
		ComponentName mComponent = new ComponentName(this, BonovoBlueToothReceiver.class);
		mediaButtonIntent.setComponent(mComponent);
		PendingIntent mediaPendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, mediaButtonIntent, 0);
		mRemoteControlClient = new RemoteControlClient(mediaPendingIntent);
		mAudioManager.registerMediaButtonEventReceiver(mComponent);
        mAudioManager.registerRemoteControlClient(mRemoteControlClient);
        mRemoteControlClient.setTransportControlFlags(
				RemoteControlClient.FLAG_KEY_MEDIA_NEXT |
				RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS |
				RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE);
        
        if (mBtMusicFocus) {
        	result = mAudioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        } else {
        	result = mAudioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        }
        
		if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED){
			mHasAudioFocus = true;
		} else {
			mHasAudioFocus = false;
		}
	}
	
	private void abandonAudioFocus() {
		if (mHasAudioFocus == true) {
			mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
			ComponentName mComponent = new ComponentName(this, BonovoBlueToothReceiver.class);
			mAudioManager.unregisterMediaButtonEventReceiver(mComponent);
			mAudioManager.unregisterRemoteControlClient(mRemoteControlClient);
			
			int result = mAudioManager.abandonAudioFocus(this);
			if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED){
				mHasAudioFocus = false;
			}
		}
	}
	
	
	public void BlueToothCallback(int Cmd, byte[] bparam) {
		if(DEB) Log.d(TAG, "BlueToothCallback cmd=" + Cmd);
		String param = "";
	
		if(bparam != null) {
			try {
				param = new String(bparam, "UTF-8");
			} catch (UnsupportedEncodingException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		if(DEB) Log.d(TAG, "BlueToothCallback param=" + param);

		switch (Cmd) {
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_IA:{ // HFP disconnect
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_IA   myBtHFPStatus:" + myBtHFPStatus);
			mHFPProfileConnected = false;
            if(getPhoneState() != PhoneState.IDLE){
				setPhoneState(PhoneState.IDLE);
				setCurrentNumber("");
				Message msg = mHandler.obtainMessage(MSG_PHONE_STATE_CHANGE);
				mHandler.sendMessage(msg);
            }
			setBtHFPStatus(false);
			recoveryAudio(AudioLevel.CODEC_LEVEL_BT_TEL);
			Message msg = mHandler.obtainMessage(MSG_HFP_STATE_CHANGE);
			mHandler.sendMessage(msg);
            
			if(mIsBtWillShutDown){
				mHandler.removeMessages(MSG_BT_SHUTDOWN);
				mHandler.sendEmptyMessage(MSG_BT_SHUTDOWN);
				mIsBtWillShutDown = false;
			}
		}
		break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_IB:{// HFP connect
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_IB   myBtHFPStatus:" + myBtHFPStatus);
			setBtHFPStatus(true);
			mHFPProfileConnected = true;
			Message msg = mHandler.obtainMessage(MSG_HFP_STATE_CHANGE);
			mHandler.sendMessage(msg);
		}
		break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_MA:{
            if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_MA");
			setMusicStatus(false);
            mHandler.removeMessages(MSG_STOP_MUSIC);
	    
            abandonAudioFocus();
	            
			recoveryAudio(AudioLevel.CODEC_LEVEL_BT_MUSIC);
			Message msg = mHandler.obtainMessage(MSG_AUDIO_STATE_CHANGE);
			mHandler.sendMessage(msg);
				
			mHandler.removeMessages(MSG_UPDATE_A2DP_TRACKINFO);
		}
		break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_MB:{
            if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_MB");
            if (mBtMusicIsEnable == true) {
	            activeAudio(AudioLevel.CODEC_LEVEL_BT_MUSIC);
				setMusicStatus(true);
				
				getAudioFocus();
					
				Message msg = mHandler.obtainMessage(MSG_AUDIO_STATE_CHANGE);
				mHandler.sendMessage(msg);
				
				Message msg2 = mHandler.obtainMessage(MSG_UPDATE_A2DP_TRACKINFO);
				mHandler.sendMessage(msg2);
            }
		}
		break;
        case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_PA:{
            Log.d(TAG, "Callback -->CMD_UNSOLICATED_PA  param:" + param);
            if(param.startsWith("0\r\n")){
                Message msg = mHandler.obtainMessage(MSG_SYNC_CONTACTS_NOTSUPPORT);
    			mHandler.sendMessage(msg);
            }else{
                mHandler.removeMessages(MSG_SYNC_CONTACTS_TIMEOUT);
                mHandler.sendEmptyMessageDelayed(MSG_SYNC_CONTACTS_TIMEOUT, DELAY_TIMEOUT_SYNC_CONTACTS);
            }
            break;
        }
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_PC:{ // sync phone book/call records complete
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_PC");
            Message msg = mHandler.obtainMessage(MSG_SYNC_CONTACTS_WRITE_DATABASE);
			mHandler.sendMessage(msg);
			break;
		}
        case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_PK: // OPP/PBAP phone book download complete
            break;
        case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_PL: // OPP download complete
            break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_PF:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_PF");			
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_QA:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_QA");
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_QB:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_QB");			
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_CZ:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_CZ");		
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_MX:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_MX");		
			break;	
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_CV:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_CV");		
			break;
		//************ add by bonovo zbiao 
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_IC:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_IC  getPhoneState():" + getPhoneState());
            activeAudio(AudioLevel.CODEC_LEVEL_BT_TEL);
			if(getPhoneState() == PhoneState.IDLE){
				setPhoneState(PhoneState.DIALING);
				Message msg = mHandler.obtainMessage(MSG_START_HANDFREE);
				mHandler.sendMessage(msg);
			}
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_ID:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_ID param:" + param + "  mBtPhoneState: " + mBtPhoneState);
            activeAudio(AudioLevel.CODEC_LEVEL_BT_TEL);
            setCurrentNumber(param);
			if(getPhoneState() == PhoneState.IDLE){
				setPhoneState(PhoneState.RINGING);
				Message msg = mHandler.obtainMessage(MSG_START_HANDFREE);
				msg.obj = param;
				mHandler.sendMessage(msg);
			}else {
				Message msg = mHandler.obtainMessage(MSG_PHONE_STATE_CHANGE);
				msg.obj = param;
				mHandler.sendMessage(msg);
			}
			
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_IF:{
                recoveryAudio(AudioLevel.CODEC_LEVEL_BT_TEL);
				if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_IF   getPhoneState():" + getPhoneState());
				setPhoneState(PhoneState.IDLE);
				setCurrentNumber("");
				Message msg = mHandler.obtainMessage(MSG_PHONE_STATE_CHANGE);
				mHandler.sendMessage(msg);
			}
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_IG:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_IG   getPhoneState():" + getPhoneState());
            activeAudio(AudioLevel.CODEC_LEVEL_BT_TEL);
			if(getPhoneState() == PhoneState.IDLE){
				setPhoneState(PhoneState.ACTIVE);
				Message msg = mHandler.obtainMessage(MSG_START_HANDFREE, " ");
				mHandler.sendMessage(msg);
			}else if(getPhoneState() == PhoneState.DIALING || getPhoneState() == PhoneState.RINGING){
				setPhoneState(PhoneState.ACTIVE);
				Message msg = mHandler.obtainMessage(MSG_PHONE_STATE_CHANGE);
				mHandler.sendMessage(msg);
			}
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_II:{
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_II");
		}
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_IJ:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_IJ");
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_IR:{
			// Phone has returned current call info (phone number)
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_IR param:" + param);
			activeAudio(AudioLevel.CODEC_LEVEL_BT_TEL);
			setCurrentNumber(param);
			
			if((getPhoneState() != PhoneState.ACTIVE)&&(getPhoneState() != PhoneState.DIALING)){
				setPhoneState(PhoneState.DIALING);
				
				Message msg = mHandler.obtainMessage(MSG_PHONE_STATE_CHANGE);
				msg.obj = cleanInfo(param);
				mHandler.sendMessage(msg);
			}else if(getPhoneState() == PhoneState.IDLE){
				setPhoneState(PhoneState.ACTIVE);
					
				Message msg = mHandler.obtainMessage(MSG_START_HANDFREE);
				msg.obj = cleanInfo(param);
				mHandler.sendMessage(msg);
			}else {
				Message msg = mHandler.obtainMessage(MSG_PHONE_STATE_CHANGE);
				msg.obj = cleanInfo(param);
				mHandler.sendMessage(msg);
			}
		}
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_IX:
			// Connected device has returned it's battery level
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_IX param:" + param);
			if(param.length() > 0){	
				Message msg = mHandler.obtainMessage(MSG_PHONE_BATTERYLEVEL, Integer.parseInt(param.substring(0,1)));
				mHandler.sendMessage(msg);
			}
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_IV:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_IV");
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_IU:
			// Connected device has returned it's signal level
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_IU param:" + param);
			if(param.length() > 0){
				Message msg = mHandler.obtainMessage(MSG_PHONE_SIGNALLEVEL, Integer.parseInt(param.substring(0,1)));
				mHandler.sendMessage(msg);
			}
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_MC:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_MC");
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_MD:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_MD");
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_MF:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_MF");
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_MG:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_MG");
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_MK:{
			// A2DP profile has just connected to a device.
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_MK");
			
			mA2DPProfileConnected = true;
			
			if(mHasFailed && mA2DPPlaying) {
				// Bluetooth module has recovered from a forced reset and was 
				// playing beforehand, so resume playback now.
				BlueToothMusicPlay();
				mHasFailed = false;
				
				Log.d(TAG, "Bluetooth module resumed playback after forced reset.");
			}
		}
		break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_ML:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_ML");
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_MM:{
				if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_MM param:" + param);
				Message msg = mHandler.obtainMessage(MSG_BT_NAME_INFO, param);
				mHandler.sendMessage(msg);
			}
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_MN:{
				if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_MN param:" + param);
				Message msg = mHandler.obtainMessage(MSG_BT_PINCODE_INFO, param);
				mHandler.sendMessage(msg);
			}
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_MU:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_MU");
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_MW:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_MW");
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_MY:{
			// A2DP profile has disconnected from remote device
				if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_MY");
            	recoveryAudio(AudioLevel.CODEC_LEVEL_BT_MUSIC);
            	
            	mA2DPProfileConnected = false;
			}
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_PE:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_PE");
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_OK:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_OK");
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_ERROR: {
			Log.d(TAG, "Bluetooth HFP - CMD_UNSOLICATED_ERROR. Error code: " + cleanInfo(param));
            Message msg = mHandler.obtainMessage(MSG_SEND_COMMANDER_ERROR, cleanInfo(param));
			mHandler.sendMessage(msg);
			break;
		}
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_IO0:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_IO0");
			mMicMuted = false;
			break;	
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_IO1:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_IO1");
			mMicMuted = true;		
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_IK:{
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_IK param:" + cleanInfo(param));
			// call waiting - number attached
			Message msg = mHandler.obtainMessage(MSG_PHONE_NEW_CALL_WAITING, cleanInfo(param));
			mHandler.sendMessage(msg);
			break;
		}
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_IL:{
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_IL");
			// Held active call and switched to call waiting
			Message msg = mHandler.obtainMessage(MSG_PHONE_HELD_ACTIVE_SWITCHED_TO_CALL_WAITING);
			mHandler.sendMessage(msg);
			break;
		}
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_IM:{
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_IM");
			// Conference call created
			Message msg = mHandler.obtainMessage(MSG_PHONE_CONFERENCE_CALL);
			mHandler.sendMessage(msg);
			break;
		}
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_IN:{
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_IN");
			// Release held or reject waiting call (hang up the inactive call)
			Message msg = mHandler.obtainMessage(MSG_PHONE_HUNG_UP_INACTIVE);
			mHandler.sendMessage(msg);
			break;
		}
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_IS:{
			Log.d(TAG, " ** Bluetooth HFP Module init complete. Firmware version: " + cleanInfo(param));
			// Bluetooth module has sent notice that it has completed it's own
			//  initialization and is now ready to receive commands.
			
			mStartComplete = true;
			mHandler.sendEmptyMessage(MSG_BT_CHECK_NAME);
			mHandler.sendEmptyMessageDelayed(MSG_BT_CHECK_PINCODE, DELAY_TIME_CHECKPINCODE);
			break;
		}
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_IT:{
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_IT");
			// Release active and switch to call waiting
			Message msg = mHandler.obtainMessage(MSG_PHONE_HUNG_UP_ACTIVE_SWITCHED_TO_CALL_WAITING);
			mHandler.sendMessage(msg);
			break;
		}
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_IQ:{
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_IQ param:" + cleanInfo(param));
			// The phone has supplied the other parties name from it's own contacts
			Intent intent = new Intent(BonovoBlueToothData.ACTION_PHONE_NAME_RECEIVED);
			intent.putExtra("name", cleanInfo(param.substring(2)));
            sendBroadcast(intent);
			break;
		}
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_PT:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_PT");
			// current call holding
			break;			
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_PV:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_PV param:" + cleanInfo(param));
			// Current phone network operator name
			Message msg1 = mHandler.obtainMessage(MSG_PHONE_NETWORKNAME, cleanInfo(param));
			mHandler.sendMessage(msg1);
			break;			
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_PZ:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_PZ");
			// Last number redial failed
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_MH:
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_MH: A2DP Elements Attributes Indication. " + param);
			// A2DP track info, don't call this often or the audio will start skipping
			int element = bparam[0];
			if(element == 1){
				a2dpTrackName = param.substring(3).trim();		
			} else if(element == 2){
				a2dpArtist = param.substring(3).trim();
			} else if(element == 3){
				a2dpAlbum = param.substring(3).trim();
			} else if(element == 4){
				// track number
			} else if(element == 5){
				// total number of tracks
			} else if(element == 7){
				//if(DEB) Log.d(TAG, "A2DP Track length: " + param.substring(3));
				
				if (mRemoteControlClient != null) {
					Bitmap artwork;
				
					// Update the track info for our remotecontrolclient
					MetadataEditor ed = mRemoteControlClient.editMetadata(false);
					artwork = BitmapFactory.decodeResource(getResources(),R.drawable.radio);
					ed.putBitmap(MetadataEditor.BITMAP_KEY_ARTWORK,artwork);
				
		        	ed.putString(MediaMetadataRetriever.METADATA_KEY_ALBUM, a2dpAlbum);
		        	ed.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, a2dpArtist);
		        	ed.putString(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST, a2dpArtist);
		        	ed.putString(MediaMetadataRetriever.METADATA_KEY_TITLE, a2dpTrackName);
		        	ed.putLong(MediaMetadataRetriever.METADATA_KEY_DURATION, trackLenMs);
		        	ed.apply();
				}
		        
				// Now we've updated all the details, broadcast the track change to all apps 
				Intent intent = new Intent(BonovoBlueToothData.A2DP_TRACK_CHANGED);
				intent.putExtra("Artist", a2dpArtist);
				intent.putExtra("Album", a2dpAlbum);
				intent.putExtra("Title", a2dpTrackName);
	            sendBroadcast(intent);
			}
			
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_MJ:
			// We call this every second to update track position and if track len changes,
			// then we flag a track change and read the new track metadata. 
			// We can call this every second without skipping unlike _MH
			//
			// Would be unneeded if the BT firmware just exposed the AVRCP track change event!
			//
			
			// Uncomment this only if you need it, otherwise it will spam up the logcat a heck of a lot
			//if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_MJ: A2DP playing status. " + param);
					
			long oldTrackLenMs = trackLenMs;
					
			trackPosMs = Long.parseLong(param.substring(1,9), 16);			
			trackLenMs = Long.parseLong(param.substring(9,17), 16);
			
			if(param.startsWith("0")){
				if (mA2DPPlaying == true) {
					mRemoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_STOPPED);
					mA2DPPlaying = false;
				}
			} else if(param.startsWith("1")){
				if (mA2DPPlaying == false) {
					mRemoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);
					mA2DPPlaying = true;
				}
			} if(param.startsWith("2")){
				if (mA2DPPlaying == true) {
					mRemoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_PAUSED);
					mA2DPPlaying = false;
				}
			} else if(param.startsWith("3")){
				mRemoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_FAST_FORWARDING);
			} else if(param.startsWith("4")){
				mRemoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_REWINDING);
			} else if(param.startsWith("F")){
				mBTErrCount = mBTErrCount + 1;
				Log.d(TAG, "Bluetooth module error count " + mBTErrCount);
				
				if (mBTErrCount > 2) {
					Log.d(TAG, "Bluetooth module error count > 2..  Resetting Bluetooth module.");
					mBTErrCount = 0;
					BlueToothReset();
				}
			}
				
			if (oldTrackLenMs != trackLenMs) {
				// track has changed, request new track details
				BonovoBlueToothSet(BonovoBlueToothRequestCmd.CMD_SOLICATED_QE);
			}
			
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_MO0:
			// Speaker Anti-Pop function
			if(DEB) Log.d(TAG, "Callback -->Speaker Anti-Pop (MO0)");
			
			// disconnect speaker
			if((mBtMusicIsEnable == true) && getMusicStatus()){
				recoveryAudio(AudioLevel.CODEC_LEVEL_BT_MUSIC);
			}
			recoveryAudio(AudioLevel.CODEC_LEVEL_BT_TEL);
			if(DEB) Log.d(TAG, "Bluetooth device disconnected from speaker.");
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_MO1:
			// Speaker Anti-Pop function
			if(DEB) Log.d(TAG, "Callback -->Speaker Anti-Pop (MO1)");
			
			// reconnect speaker
			if((mBtMusicIsEnable == true) && getMusicStatus()){
				activeAudio(AudioLevel.CODEC_LEVEL_BT_MUSIC);
			}
			if(getPhoneState() != PhoneState.IDLE){
				activeAudio(AudioLevel.CODEC_LEVEL_BT_TEL);
			}
			if(DEB) Log.d(TAG, "Bluetooth device reconnected to speaker.");
			break;
		case BonovoBlueToothUnsolicatedCmd.CMD_UNSOLICATED_UNKNOWN:{
			if(DEB) Log.d(TAG, "Callback -->CMD_UNSOLICATED_UNKNOWN param: " + cleanInfo(param));
			// unknown response, log for further study
			break;
		}
		default:
			break;
		}

	}

    private class Contact {
        private String mName;
        private String mNumber;

        public Contact() {
            super();
        }

        public Contact(String name, String number){
            super();
            mName = name;
            mNumber = number;
        }

        public String getName(){
            return mName;
        }

        public String getNumber(){
            return mNumber;
        }

        public void setName(String name){
            mName = name;
        }

        public void setNumber(String number){
            mNumber = number;
        }

        public String toString(){
            return ("Contact name:" + mName + " number:" + mNumber);
        }
    }
	
	/*!
	 * sync contacts
	 */		
	public	void SynchPhoneBook(String name,String js){
	    if(mIsSyncingContacts){
    	    Contact contact = new Contact(name, js);
    	    mListContacts.add(contact);
            Message msg = mHandler.obtainMessage(MSG_SYNC_CONTACTS_READ_COUNT, mListContacts.size(), 0);
            mHandler.sendMessage(msg);
	    }
/*		ContentValues values = new ContentValues();
		values.put(People.NAME,name);
		Uri uri = getContentResolver().insert(People.CONTENT_URI, values);
		Uri numberUri = Uri.withAppendedPath(uri, People.Phones.CONTENT_DIRECTORY);
		values.clear();
		values.put(Contacts.Phones.TYPE, People.Phones.TYPE_MOBILE);
		values.put(People.NUMBER, js);
		getContentResolver().insert(numberUri, values);
*/	
	}

    public void AddContactsInDataBase(Context context, List<Contact> list){
        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
        ArrayList<ContentProviderOperation> batchOps = new ArrayList<ContentProviderOperation>();
        int batch = 0, index = 0;

//      for(int i = 0; i<list.size(); i++){
//          index = ops.size();
//          ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
//              .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
//              .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
//              .withValue(ContactsContract.RawContacts.AGGREGATION_MODE, ContactsContract.RawContacts.AGGREGATION_MODE_DEFAULT)
//              .withYieldAllowed(true)
//              .build());
//          ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
//              .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, index)
//              .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
//              .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, list.get(i).getName())
//              .withYieldAllowed(true)
//              .build());
//          ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
//              .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, index)
//              .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
//              .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, list.get(i).getNumber())
//              .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, Phone.TYPE_MOBILE)
//              .withYieldAllowed(true)
//              .build());
//      }

        batch = list.size()/MAX_COUNT_CONTACTS_PRE_SYNC;
        for(int j=0; j<= batch; j++){
            ops.clear();
            for(int i=j*MAX_COUNT_CONTACTS_PRE_SYNC; i<(j+1)*MAX_COUNT_CONTACTS_PRE_SYNC && (i < list.size()); i++){
                index = ops.size();
                ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .withValue(ContactsContract.RawContacts.AGGREGATION_MODE, ContactsContract.RawContacts.AGGREGATION_MODE_DEFAULT)
                    .withYieldAllowed(true)
                    .build());
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, index)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, list.get(i).getName())
                    .withYieldAllowed(true)
                    .build());
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, index)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, list.get(i).getNumber())
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, Phone.TYPE_MOBILE)
                    .withYieldAllowed(true)
                    .build());
            }

            try {
                context.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
            }catch(RemoteException e){
                Log.e(TAG, "Cann't write database. Happened RemoteException Error.");
                e.printStackTrace();
            }catch(OperationApplicationException e){
                Log.e(TAG, "Cann't write database. Happened OperationApplicationException Error.");
                e.printStackTrace();
            }
        }
    }

    public void AddContactsNoDuplicateNames(Context context, List<Contact> list){

        for(int i = 0; i<list.size(); i++){
            ContentValues value = new ContentValues();
            Uri rawContactUri = context.getContentResolver().insert(ContactsContract.RawContacts.CONTENT_URI, value);
            long rawContactId = ContentUris.parseId(rawContactUri);
            Log.d(TAG, "====== [rawContactId:" + rawContactId + "] " + list.get(i).toString());

            value.clear();
            value.put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId);
            value.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE);
            value.put(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, list.get(i).getName());
            context.getContentResolver().insert(ContactsContract.Data.CONTENT_URI, value);

            value.clear();
            value.put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId);
            value.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);
            value.put(ContactsContract.CommonDataKinds.Phone.NUMBER, list.get(i).getNumber());
            value.put(ContactsContract.CommonDataKinds.Phone.TYPE, Phone.TYPE_MOBILE);
            context.getContentResolver().insert(ContactsContract.Data.CONTENT_URI, value);
        }
    }

	class BonovoBlueToothUnsolicatedCmd {
		// Unsolicated Cmd	--  Sent by the HFP device to indicate status
		public static final int CMD_UNSOLICATED_IS = 0;		// Initialization complete
		public static final int CMD_UNSOLICATED_IA = 1;		// HFP disconnect
		public static final int CMD_UNSOLICATED_IB = 2;		// HFP connect
		public static final int CMD_UNSOLICATED_IC = 3;		// Outgoing call
		public static final int CMD_UNSOLICATED_ID = 4;		// Incoming call
		public static final int CMD_UNSOLICATED_IF = 5;		// Hang up 
		public static final int CMD_UNSOLICATED_IG = 6;		// Pick up
		public static final int CMD_UNSOLICATED_II = 7;		// Entered pairing mode
		public static final int CMD_UNSOLICATED_IJ = 8;		// Exited pairing mode
		public static final int CMD_UNSOLICATED_IR = 9;		// Current call info
		public static final int CMD_UNSOLICATED_IV = 10;	// HFP connecting
		public static final int CMD_UNSOLICATED_IU = 11;	// AG (phone) signal level changed
		public static final int CMD_UNSOLICATED_MA = 12;	// AV suspend / stop
		public static final int CMD_UNSOLICATED_MB = 13;	// AV playing
		public static final int CMD_UNSOLICATED_MC = 14;	// HFP audio connected
		public static final int CMD_UNSOLICATED_MD = 15;	// HFP audio disconnected
		public static final int CMD_UNSOLICATED_MF = 16;	// Report Auto Answer and Power On Auto Connection status
		public static final int CMD_UNSOLICATED_MG = 17;	// Report current HFP status
		public static final int CMD_UNSOLICATED_ML = 18;	// Report current AVRCP status
		public static final int CMD_UNSOLICATED_MM = 19;	// Report current local device name
		public static final int CMD_UNSOLICATED_MN = 20;	// Report current local device PIN	
		public static final int CMD_UNSOLICATED_MU = 21;	// Report current A2DP status
		public static final int CMD_UNSOLICATED_MW = 22;	// Report HFP module software version
		public static final int CMD_UNSOLICATED_MX = 23;	// Report paired list
		public static final int CMD_UNSOLICATED_MY = 24;	// A2DP disconnected
		public static final int CMD_UNSOLICATED_PA = 25;	// Phonebook storage supported
		public static final int CMD_UNSOLICATED_PN = 26;	// SPP disconnected
		public static final int CMD_UNSOLICATED_PC = 27;	// Phonebook sync ended
		public static final int CMD_UNSOLICATED_PB = 28;	// One phonebook entry indication (via AT command)
		public static final int CMD_UNSOLICATED_PK = 29;	// One phonebook entry indication
		public static final int CMD_UNSOLICATED_PL = 30;	// OPP disconnect
		public static final int CMD_UNSOLICATED_PE = 31;	// Voice dial started (siri / google now session)
		public static final int CMD_UNSOLICATED_PF = 32;	// Voice dial stopped
		public static final int CMD_UNSOLICATED_WA = 33;	// PBAP profile connected
		public static final int CMD_UNSOLICATED_WB = 34;	// PBAP started downloading
		public static final int CMD_UNSOLICATED_WC = 35;	// PBAP disconnected
		public static final int CMD_UNSOLICATED_WD = 36;	// PBAP download complete
		public static final int CMD_UNSOLICATED_WN = 37;	// AG (phone) has no PBAP support
		public static final int CMD_UNSOLICATED_QB = 38;	// Pairing successful
		public static final int CMD_UNSOLICATED_QA = 39;	// Pairing request

		//
		public static final int CMD_UNSOLICATED_IX = 40;	// AG (phone) battery level changed
		public static final int CMD_UNSOLICATED_MK = 41;	// A2DP profile connected
		public static final int CMD_UNSOLICATED_QH = 42; 	// HFP_PROFILE_VERSION
		public static final int CMD_UNSOLICATED_ERROR = 43;	
		public static final int CMD_UNSOLICATED_OK = 44;	
		public static final int CMD_UNSOLICATED_CZ = 45;	// ?
		public static final int CMD_UNSOLICATED_CV = 46;	// ?
		// add by bonovo zbiao
		public static final int CMD_UNSOLICATED_IO0 = 47;	// Mic muted
		public static final int CMD_UNSOLICATED_IO1 = 48;	// Mic unmuted

		public static final int CMD_UNSOLICATED_IK = 49;	// Call Waiting
		public static final int CMD_UNSOLICATED_IL = 50;	// Held active call and switched to call waiting
		public static final int CMD_UNSOLICATED_IM = 51;	// Conference call
		public static final int CMD_UNSOLICATED_IN = 52;	// Release held call and reject call waiting
		
		public static final int CMD_UNSOLICATED_IQ = 53;	// Incoming call with name indication
		public static final int CMD_UNSOLICATED_PT = 54;	// Current call holding
		public static final int CMD_UNSOLICATED_PV = 55;	// Current phone network operator name
		public static final int CMD_UNSOLICATED_PZ = 56;	// Last number redial failed
		public static final int CMD_UNSOLICATED_PM = 57;	// SPP Connect or SPP Data Indication
		public static final int CMD_UNSOLICATED_MH = 58;	// AVRCP current element attributes
		public static final int CMD_UNSOLICATED_IT = 59;	// Release active call and switched to call waiting

		public static final int CMD_UNSOLICATED_MO0 = 60;	// Anti-pop - Turn off speaker notice
		public static final int CMD_UNSOLICATED_MO1 = 61;	// Anti-pop - Turn speaker back on notice
		public static final int CMD_UNSOLICATED_MJ = 62;		// A2DP Playback state
		public static final int CMD_UNSOLICATED_UNKNOWN = 63;	// Unknown response
		public static final int CMD_UNSOLICATED_MAX = 64;
	}		

	class BonovoBlueToothRequestCmd {
		public static final int CMD_SOLICATED_CA = 0;	// Enter pairing mode
		public static final int CMD_SOLICATED_CB = 1;	// Cancel pairing mode
		public static final int CMD_SOLICATED_CC = 2;	// Connect to handset
		public static final int CMD_SOLICATED_CD = 3;	// Disconnect from handset
		public static final int CMD_SOLICATED_CE = 4;	// Answer call
		public static final int CMD_SOLICATED_CF = 5;	// Reject call
		public static final int CMD_SOLICATED_CG = 6;	// End call
		public static final int CMD_SOLICATED_CH = 7;	// Redial
		public static final int CMD_SOLICATED_CI = 8;	// Voice dial (Siri / Google Now activation)
		public static final int CMD_SOLICATED_CJ = 9;	// Cancel voice dial
		public static final int CMD_SOLICATED_CK = 10;	// Volume up
		public static final int CMD_SOLICATED_CL = 11;	// Volume down
		public static final int CMD_SOLICATED_CM = 12;	// Mute / Unmute mic
		public static final int CMD_SOLICATED_CO = 13;	// Transfer audio to/from handset
		public static final int CMD_SOLICATED_CW = 14;	// Dial one call
		public static final int CMD_SOLICATED_CX = 15;	// Send DTMF 
		public static final int CMD_SOLICATED_CY = 16;	// Query HFP status
		public static final int CMD_SOLICATED_CN = 17;	// Start inquiry AG
		public static final int CMD_SOLICATED_CP = 18;	// Stop inquiry AG

		public static final int CMD_SOLICATED_WI = 19;	// ?
		public static final int CMD_SOLICATED_MA = 20;	// Play / pause music
		public static final int CMD_SOLICATED_MC = 21;	// Stop music
		public static final int CMD_SOLICATED_MD = 22;	// Forward
		public static final int CMD_SOLICATED_ME = 23;	// Backward
		public static final int CMD_SOLICATED_MV = 24;	// Query A2DP status
		public static final int CMD_SOLICATED_MO = 25;	// Query AVRCP status

		public static final int CMD_SOLICATED_PA = 26;	// Select SIM phonebook storage
		public static final int CMD_SOLICATED_PB = 27;	// Select phone memory storage
		public static final int CMD_SOLICATED_PH = 28;	// Select dialed call list storage
		public static final int CMD_SOLICATED_PI = 29;	// Select received call list storage 
		public static final int CMD_SOLICATED_PJ = 30;	// Select missed call list storage
		public static final int CMD_SOLICATED_PF = 31;	// Download all phonebook / call list from selected storage
		public static final int CMD_SOLICATED_PE = 32;	// Accept OPP connection
		public static final int CMD_SOLICATED_PG = 33;	// Reject or abort OPP connection
		public static final int CMD_SOLICATED_QA = 34;	// Start PBAP connection
		public static final int CMD_SOLICATED_QB = 35;	// Download phonebook item (via PBAP)
		public static final int CMD_SOLICATED_QC = 36;	// Close PBAP connection

		public static final int CMD_SOLICATED_CZ = 37;	// Reset Bluetooth moduke
		public static final int CMD_SOLICATED_CV = 38;	// Delete Paired Information and Enter Pairing Mode
		public static final int CMD_SOLICATED_MY = 39;	// Query HFP module software version
		public static final int CMD_SOLICATED_MG = 40;	// Enable auto connection
		public static final int CMD_SOLICATED_MH = 41;	// Disable auto connection
		public static final int CMD_SOLICATED_MP = 42;	// Enable auto answer
		public static final int CMD_SOLICATED_MQ = 43;  // Disable auto answer
		public static final int CMD_SOLICATED_MF = 44;	// Query Auto Answer and Power On Auto Connection Configuration
		public static final int CMD_SOLICATED_MM = 45;	// Change local device name
		public static final int CMD_SOLICATED_MN = 46;	// Change local device PIN
		public static final int CMD_SOLICATED_MX = 47;	// Query paired list
		public static final int CMD_SOLICATED_DA = 48;	// ?
		
		public static final int CMD_SOLICATED_CQ = 49;	// Release held or reject waiting call
		public static final int CMD_SOLICATED_CR = 50;  // Release active, accept waiting call
		public static final int CMD_SOLICATED_CS = 51;	// Hold active, accept waiting call
		public static final int CMD_SOLICATED_CT = 52;	// Conference all calls
		
		public static final int CMD_SOLICATED_MZ = 53;	// Query HFP module local address
		public static final int CMD_SOLICATED_QD = 54;	// Get play status (a2dp / avrcp)
		public static final int CMD_SOLICATED_QE = 55;	// Get element attributes (a2dp / avrcp)
		
		public static final int CMD_SOLICATED_PP = 56;	// Send data via SPP
		
		public static final int CMD_SOLICATED_MI = 57;	//
		public static final int CMD_SOLICATED_MJ = 58;	// 
		
		public static final int CMD_SOLICATED_MAX = 59;
	}
	
	class BonovoBlueToothData {
		public final static String ACTION_DATA_IAIB_CHANGED = "android.intent.action.DATA_IAIB_CHANGED";
		public final static String ACTION_DATA_MAMB_CHANGED = "android.intent.action.DATA_MAMB_CHANGED";
		public final static String ACTION_PHONE_STATE_CHANGED = "android.intent.action.PHONE_STATE_CHANGED";
        public final static String ACTION_SYNC_CONTACTS_READ_COUNT = "android.intent.action.SYNC_CONTACTS_READ_COUNT";
        public final static String ACTION_SYNC_CONTACTS_WRITE_DATABASE = "android.intent.action.SYNC_CONTACTS_START_WRITE_DATABASE";
		public final static String ACTION_SYNC_CONTACTS_COMPLETE = "android.intent.action.SYNC_CONTACTS_COMPLETE";
        public final static String ACTION_SYNC_CONTACTS_TIMEOUT = "android.intent.action.SYNC_CONTACTS_TIMEOUT";
        public final static String ACTION_SYNC_CONTACTS_NOT_SUPPORT = "android.intent.action.SYNC_CONTACTS_NOT_SUPPORT";
        public final static String ACTION_SEND_COMMANDER_ERROR = "android.intent.action.SEND_COMMANDER_ERROR";
        public final static String ACTION_PHONE_NEW_CALL_WAITING = "android.intent.action.PHONE_NEW_CALL_WAITING";
        public final static String ACTION_PHONE_HELD_ACTIVE_SWITCHED_TO_CALL_WAITING = "android.intent.action.PHONE_HELD_ACTIVE_SWITCHED_TO_CALL_WAITING";
        public final static String ACTION_PHONE_CONFERENCE_CALL = "android.intent.action.PHONE_CONFERENCE_CALL";
        public final static String ACTION_PHONE_HUNG_UP_INACTIVE = "android.intent.action.PHONE_HUNG_UP_INACTIVE";
        public final static String ACTION_PHONE_HUNG_UP_ACTIVE_SWITCHED_TO_CALL_WAITING = "android.intent.action.PHONE_HUNG_UP_ACTIVE_SWITCHED_TO_CALL_WAITING";
        public final static String ACTION_PHONE_NETWORK_NAME_CHANGED = "android.intent.action.PHONE_NETWORK_NAME_CHANGED";
        public final static String ACTION_PHONE_SIGNAL_LEVEL_CHANGED = "android.intent.action.PHONE_SIGNAL_LEVEL_CHANGED";
        public final static String ACTION_PHONE_BATTERY_LEVEL_CHANGED = "android.intent.action.PHONE_BATTERY_LEVEL_CHANGED";
        public final static String ACTION_PHONE_NAME_RECEIVED = "android.intent.action.PHONE_NAME_RECEIVED";
        public final static String A2DP_TRACK_CHANGED = "android.intent.action.A2DP_TRACK_CHANGED";
        
		public final static String NAME = "name";
		public final static String LEVEL = "level";
		public final static String PHONE_NUMBER = "phone_number";
		public final static String PHONE_STATE = "phone_status";
		public final static String ACTION_BT_NAME = "android.intent.action.BLUETOOTH_NAME";
		public final static String ACTION_BT_PINCODE = "android.intent.action.BLUETOOTH_PINCODE";
		public final static String ACTION_INFO_BT_NAME = "bt_name";
		public final static String ACTION_INFO_BT_PINCODE = "bt_pin_code";
		public final static String HFP_STATUS = "hfp_status";
		public final static String A2DP_STATUS = "a2dp_status";
        public final static String KEY_SYNC_CONTACTS_COUNT = "contacts_count";
	}
	
	@Override
	public void onAudioFocusChange(int focusChange) {
		// TODO Auto-generated method stub
		if(DEB) Log.d(TAG, "onAudioFocusChange focusChange: " + focusChange);
		switch (focusChange) {
		case AudioManager.AUDIOFOCUS_GAIN:
			break;
		case AudioManager.AUDIOFOCUS_LOSS:
			BlueToothMusicStop();
			break;
		case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
			break;
		case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
			break;

		default:
			break;
		}
	}
}
