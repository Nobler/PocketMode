package com.wdjhzw.pocketmode;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Created by houzhiwei on 16/5/23.
 */
public class MainService extends Service {
    public static final String ACTION_SHOW_BLOCKED_VIEW = "com.wdjhzw.pocketmode.SHOW_BLOCKED_VIEW";
    public static final String ACTION_UPDATE_BLOCKED_VIEW = "com.wdjhzw.pocketmode.UPDATE_BLOCKED_VIEW";
    public static final String ACTION_HIDE_BLOCKED_VIEW = "com.wdjhzw.pocketmode.HIDE_BLOCKED_VIEW";
    public static final String ACTION_SET_BLOCKED_INFO_COORDINATE = "com.wdjhzw.pocketmode.SET_BLOCKED_INFO_COORDINATE";
    public static final String EXTRA_IS_BLOCKED_INFO_VISIBLE = "com.wdjhzw.pocketmode.extra.IS_BLOCKED_INFO_VISIBLE";
    public static final String EXTRA_BLOCKED_INFO_COORDINAT = "com.wdjhzw.pocketmode.extra.BLOCKED_INFO_COORDINAT";
    public static final String TAG = "MainService";
    private int mRepeatCount;
    private SensorEventReceiver mReceiver;
    private BlockedView mBlockedView;
    private WindowManager.LayoutParams mLayoutParams;
    private WindowManager mWindowManager;
    private boolean mIsBlockedViewShown;
    private ProgressBar mProgressBar;
    private TextView mTextView;
    private int mBlockedInfoHeight;
    private int mBlockedInfoCoordinate;
    private int mScreenHeight;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);

        mReceiver = new SensorEventReceiver(this, sensorManager, proximitySensor);
        registerReceiver(mReceiver, filter);

        mRepeatCount = getResources().getInteger(R.integer.repeat_count);
        mWindowManager = ((WindowManager) getSystemService(Context.WINDOW_SERVICE));
        initBlockedViewLayoutParams();
        inflateBlockedView();
        mIsBlockedViewShown = false;

        mBlockedInfoCoordinate = PreferenceManager.getDefaultSharedPreferences(MainService
                .this).getInt(MainSettingsFragment.KEY_BLOCKED_INFO_POS,
                CoordinatePickerPreference.DEFAULT_VALUE);
        mScreenHeight = ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay().getHeight();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.e(TAG, "onStartCommand:");

        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();

            // SensorEventReceiver use the action to call MainService's feature.
            switch (action) {
                case ACTION_SHOW_BLOCKED_VIEW:
                    showBlockedView();
                    break;
                case ACTION_UPDATE_BLOCKED_VIEW:
                    setBlockedInfoVisibility(intent.getBooleanExtra
                            (EXTRA_IS_BLOCKED_INFO_VISIBLE, true));
                    break;
                case ACTION_SET_BLOCKED_INFO_COORDINATE:
                    setBlockedInfoCoordinate(intent.getIntExtra(EXTRA_BLOCKED_INFO_COORDINAT,
                            CoordinatePickerPreference.DEFAULT_VALUE));
                    break;
                case ACTION_HIDE_BLOCKED_VIEW:
                    hideBlockedView();
                    break;
            }
        }

        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        unregisterReceiver(mReceiver);
    }

    private void initBlockedViewLayoutParams() {
        mLayoutParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams
                .TYPE_SYSTEM_ERROR, WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED, PixelFormat
                .TRANSLUCENT);

        mLayoutParams.windowAnimations = R.style.BlockedView;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mLayoutParams.flags |= WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            mLayoutParams.flags |= WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
        }
    }

    private void inflateBlockedView() {
        mBlockedView = (BlockedView) ((LayoutInflater) getSystemService(Context
                .LAYOUT_INFLATER_SERVICE)).inflate(R.layout.blocked_view, null);

        mTextView = (TextView) mBlockedView.findViewById(R.id.blocked_info);
        if (PreferenceManager.getDefaultSharedPreferences(MainService
                .this).getBoolean(MainSettingsFragment.KEY_SHOW_BLOCKED_INFO, true)) {
            mTextView.setVisibility(View.VISIBLE);

            ViewTreeObserver vto = mTextView.getViewTreeObserver();
            vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    mTextView.getViewTreeObserver().removeGlobalOnLayoutListener(this);

                    mBlockedInfoHeight = mTextView.getHeight();
                    mTextView.setY((mScreenHeight - mBlockedInfoHeight / 2) / 100 *
                            mBlockedInfoCoordinate);
                }
            });
        } else {
            mTextView.setVisibility(View.INVISIBLE);
        }

        mProgressBar = (ProgressBar) mBlockedView.findViewById(R.id.progressBar);
        mBlockedView.setOnKeyStateChangeListener(new BlockedView.OnKeyStateChangeListener() {
            /**
             * <p>
             *     Whether the blocked view is transparent.
             * </p>
             *
             * <p>
             *     When double key repeat count increase to 20, make blocked view being
             *     transparent, blocked view will still dispatch key event. Users feel like the
             *     blocked view is gone(actually not), then they release the double key, blocked
             *     view go away.
             * </p>
             *
             * <p>
             *     This design is to avoid, when blocked view is gone, one of volume keys may be
             *     still in down state, Lanuncher or Keyguard will dispatch the key event which
             *     will result in sound notifaction showing. It is bad user experience.
             *     There may exists a more elegant solution
             * </p>
             */
            private boolean isBlockedViewTransparent = false;

            @Override
            public void onKeyStateChange(int keyState, int repeatCount) {
                if (keyState == BlockedView.KeyState.STATE_DOUBLE_KEY_DOWN_SIMULTANEOUSLY) {
                    mProgressBar.setVisibility(View.VISIBLE);
                    mProgressBar.setProgress(repeatCount);

                    if (!isBlockedViewTransparent) {
                        if (repeatCount > mRepeatCount) {
                            Log.e(TAG, "repeat count");
                            isBlockedViewTransparent = true;
                            mBlockedView.setBackgroundResource(android.R.color.transparent);
                            setTextViewVisibility(View.INVISIBLE);
                        }

                        return;
                    }
                } else if (keyState == BlockedView.KeyState.STATE_DOUBLE_KEY_UP) {
                    if (isBlockedViewTransparent) {
                        Log.e(TAG, "double key up");
                        isBlockedViewTransparent = false;
                        hideBlockedView();
                        mBlockedView.setBackgroundResource(R.drawable.bg_blocked_view);
                        setTextViewVisibility(View.VISIBLE);

                        return;
                    }
                }

                mProgressBar.setVisibility(View.INVISIBLE);
            }
        });
    }

    private void setTextViewVisibility(int visibility) {
        if (!PreferenceManager.getDefaultSharedPreferences(MainService.this).getBoolean
                (MainSettingsFragment.KEY_SHOW_BLOCKED_INFO, true)) {
            mTextView.setVisibility(View.INVISIBLE);
        } else {
            mTextView.setVisibility(visibility);
        }
    }

    private void showBlockedView() {
        if (!mIsBlockedViewShown) {
            mWindowManager.addView(mBlockedView, mLayoutParams);
            mIsBlockedViewShown = true;
        }
    }

    private void setBlockedInfoVisibility(boolean isBlockedInfoVisible) {
        if (mTextView != null) {
            mTextView.setVisibility(isBlockedInfoVisible ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void setBlockedInfoCoordinate(int coordinate) {
        if (mTextView != null) {
            mTextView.setY((mScreenHeight - mBlockedInfoHeight / 2) / 100 * coordinate);
        }
    }

    private void hideBlockedView() {
        if (mIsBlockedViewShown) {
            mWindowManager.removeView(mBlockedView);
            mIsBlockedViewShown = false;
        }
    }

    public static class BootReceiver extends BroadcastReceiver {
        public BootReceiver() {
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            context.startService(new Intent(context, MainService.class));
            Log.e("BootReceiver", "boot");
        }
    }
}
