/*
 * Copyright Statement:
 *
 *   This software/firmware and related documentation ("MediaTek Software") are
 *   protected under relevant copyright laws. The information contained herein is
 *   confidential and proprietary to MediaTek Inc. and/or its licensors. Without
 *   the prior written permission of MediaTek inc. and/or its licensors, any
 *   reproduction, modification, use or disclosure of MediaTek Software, and
 *   information contained herein, in whole or in part, shall be strictly
 *   prohibited.
 *
 *   MediaTek Inc. (C) 2016. All rights reserved.
 *
 *   BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 *   THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 *   RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER
 *   ON AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL
 *   WARRANTIES, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED
 *   WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR
 *   NONINFRINGEMENT. NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH
 *   RESPECT TO THE SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY,
 *   INCORPORATED IN, OR SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES
 *   TO LOOK ONLY TO SUCH THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO.
 *   RECEIVER EXPRESSLY ACKNOWLEDGES THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO
 *   OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES CONTAINED IN MEDIATEK
 *   SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK SOFTWARE
 *   RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 *   STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S
 *   ENTIRE AND CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE
 *   RELEASED HEREUNDER WILL BE, AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE
 *   MEDIATEK SOFTWARE AT ISSUE, OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE
 *   CHARGE PAID BY RECEIVER TO MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 *   The following software/firmware and/or related documentation ("MediaTek
 *   Software") have been modified by MediaTek Inc. All revisions are subject to
 *   any receiver's applicable license agreements with MediaTek Inc.
 */
package com.mediatek.camera.feature.setting.focus;

import android.annotation.TargetApi;
import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import com.mediatek.camera.common.debug.LogHelper;
import com.mediatek.camera.common.debug.LogUtil;
import com.mediatek.camera.common.device.v2.Camera2CaptureSessionProxy;
import com.mediatek.camera.common.device.v2.Camera2Proxy;
import com.mediatek.camera.common.setting.ICameraSetting;
import com.mediatek.camera.common.setting.ISettingManager;
import com.mediatek.camera.common.utils.CameraUtil;
import com.mediatek.camera.portability.SystemProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;


/**
 * This class used to configure focus value to capture request.
 * and configure those settings value which have restriction with focus but
 * without ui
 * and just used for api2.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class FocusCaptureRequestConfigure implements ICameraSetting.ICaptureRequestConfigure, IFocus
        .Listener, IFocusController {
    private static final LogUtil.Tag TAG = new LogUtil.Tag(
            FocusCaptureRequestConfigure.class.getSimpleName());
    private Focus mFocus;
    private CameraCharacteristics mCameraCharacteristics;
    private FocusStateListener mFocusStateListener;
    private int mLastResultAFState = CaptureResult.CONTROL_AF_STATE_INACTIVE;
    private Integer mAeState = CaptureResult.CONTROL_AE_STATE_INACTIVE;
    private long mLastAfTriggerFrameNumber = -1;
    // Last frame for which CONTROL_AF_STATE was received.
    private long mLastControlAfStateFrameNumber = -1;
    private List<String> mSupportedFocusModeList = Collections.<String>emptyList();
    private boolean mNeedWaitActiveScanDone = false;
    private ISettingManager.SettingDevice2Requester mDevice2Requester;
    private int mCurrentFocusMode = CameraMetadata.CONTROL_AF_MODE_OFF;
    private MeteringRectangle[] mAERegions = ZERO_WEIGHT_3A_REGION;
    private MeteringRectangle[] mAFRegions = ZERO_WEIGHT_3A_REGION;
    private Rect mCropRegion = new Rect();

    private static final int TEMPLATE_STILL_CAPTURE = 2;
    private static final int TEMPLATE_ZERO_SHUTTER_LAG = 5;

    private static final String LOG_ROIS_PROP = "vendor.mtk.camera.app.3a.debug.log";
    private static boolean sIsLogAeAfRegion = SystemProperties.getInt(LOG_ROIS_PROP, 0) == 1;
    private long mStartTime = 0;

    private static final String FLASH_OFF_VALUE = "off";
    private static final String FLASH_AUTO_VALUE = "auto";
    private static final String FLASH_ON_VALUE = "on";

    private ConcurrentLinkedQueue<String> mFocusQueue = new ConcurrentLinkedQueue<>();
    private static final String AUTOFOCUS = "autoFocus";
    private static final String CANCEL_AUTOFOCUS = "cancelAutoFocus";

    /** Metering region weight between 0 and 1.
     *
     * <p>
     * This value has been tested on Nexus 5 and Shamu, but will need to be
     * tuned per device depending on how its ISP interprets the metering box and weight.
     * </p>
     */
    private static final float REGION_WEIGHT = 0.022f;
    /** camera2 API metering region weight. */
    private static final int CAMERA2_REGION_WEIGHT = (int)
            (lerp(MeteringRectangle.METERING_WEIGHT_MIN, MeteringRectangle.METERING_WEIGHT_MAX,
                    REGION_WEIGHT));
    private static final String SLOW_MOTION
            = "com.mediatek.camera.feature.mode.slowmotion.SlowMotionMode";
    private static final MeteringRectangle[] ZERO_WEIGHT_3A_REGION = new MeteringRectangle[]{
            new MeteringRectangle(0, 0, 0, 0, 0)
    };

    private boolean mDisableUpdateFocusState = false;
    private final Object mLock = new Object();
    private Handler mModeHandler = null;
    /**
     * Focus mode enum value.
     */
    enum FocusEnum {
        INFINITY(0),
        AUTO(1),
        MACRO(2),
        CONTINUOUS_VIDEO(3),
        CONTINUOUS_PICTURE(4),
        EDOF(5);

        private int mValue = 0;

        private FocusEnum(int value) {
            this.mValue = value;
        }

        /**
         * Get enum value which is in integer.
         *
         * @return The enum value.
         */
        public int getValue() {
            return this.mValue;
        }

        /**
         * Get enum name which is in string.
         *
         * @return The enum name.
         */
        public String getName() {
            return this.toString();
        }

    }

    /**
     * The construction function.
     *
     * @param focus            the Focus class object.
     * @param device2Requester Requester used to request to config capture request.
     */
    public FocusCaptureRequestConfigure(Focus focus, ISettingManager.SettingDevice2Requester
            device2Requester) {
        mFocus = focus;
        mDevice2Requester = device2Requester;
        mModeHandler = new Handler(Looper.myLooper());
    }

    @Override
    public void setCameraCharacteristics(CameraCharacteristics characteristics) {
        mDisableUpdateFocusState = false;
        mNeedWaitActiveScanDone = false;
        mAeState = CaptureResult.CONTROL_AE_STATE_INACTIVE;
        mCameraCharacteristics = characteristics;
        initPlatformSupportedValues();
        if (CameraUtil.hasFocuser(characteristics)) {
            initAppSupportedEntryValues();
            initSettingEntryValues();
            initFocusMode(getSettingEntryValues());
        }
    }

    @Override
    public void configCaptureRequest(CaptureRequest.Builder captureBuilder) {
        if (captureBuilder == null) {
            LogHelper.d(TAG, "[configCaptureRequest] captureBuilder is null");
            return;
        }
        LogHelper.d(TAG, "[configCaptureRequest] mCurrentFocusMode = " + mCurrentFocusMode);
        addBaselineCaptureKeysToRequest(captureBuilder);
    }

    @Override
    public void configSessionSurface(List<Surface> surfaces) {

    }

    @Override
    public Surface configRawSurface() {
        return null;
    }

    @Override
    public CameraCaptureSession.CaptureCallback getRepeatingCaptureCallback() {
        return mPreviewCallback;
    }

    @Override
    public void sendSettingChangeRequest() {
        mDevice2Requester.createAndChangeRepeatingRequest();
    }

    @Override
    public void setFocusStateListener(FocusStateListener listener) {
        synchronized (mLock) {
            mFocusStateListener = listener;
        }
    }

    @Override
    public void updateFocusMode(String currentValue) {
        if (getSettingEntryValues().contains(currentValue)) {
            mCurrentFocusMode = convertStringToEnum(currentValue);
            sendSettingChangeRequest();
        }
    }

    @Override
    public void overrideFocusMode(String currentValue, List<String> supportValues) {
        LogHelper.d(TAG, "[overrideFocusMode] currentValue = " + currentValue + ",supportValues =" +
                " " + supportValues);
        if (getSettingEntryValues().contains(currentValue)) {
            mCurrentFocusMode = convertStringToEnum(currentValue);
        }
    }

    @Override
    public void autoFocus() {
        LogHelper.d(TAG, "[autoFocus]");
        sendAutoFocusTriggerCaptureRequest(false);
    }

    @Override
    public void restoreContinue() {
        LogHelper.d(TAG, "[restoreContinue]");
        if (getSettingEntryValues().indexOf(convertEnumToString(FocusEnum
                .CONTINUOUS_PICTURE.getValue())) > 0 || getSettingEntryValues()
                .indexOf(convertEnumToString(FocusEnum
                        .CONTINUOUS_VIDEO.getValue())) > 0) {
            mCurrentFocusMode = convertStringToEnum(mFocus.getValue());
            mAFRegions = ZERO_WEIGHT_3A_REGION;
            mAERegions = ZERO_WEIGHT_3A_REGION;
            sendSettingChangeRequest();
        }
    }

    @Override
    public void cancelAutoFocus() {
        LogHelper.d(TAG, "[cancelAutoFocus] ");
        sendAutoFocusCancelCaptureRequest();
    }

    @Override
    public void updateFocusCallback() {
        mDevice2Requester.createAndChangeRepeatingRequest();
    }

    @Override
    public void disableUpdateFocusState(boolean disable) {
        mDisableUpdateFocusState = disable;
    }

    @Override
    public void resetConfiguration() {
        if (!mFocusQueue.isEmpty()) {
            mFocusQueue.clear();
        }
    }

    @Override
    public void setWaitCancelAutoFocus(boolean needWaitCancelAutoFocus) {
        if (needWaitCancelAutoFocus) {
            doAfTriggerBeforeCapture();
        } else {
            synchronized (mFocusQueue) {
                if (!mFocusQueue.isEmpty() && CANCEL_AUTOFOCUS.equals(mFocusQueue.peek())) {
                    mFocusQueue.clear();
                    cancelAutoFocus();
                    mFocus.resetTouchFocusWhenCaptureDone();
                }
            }
        }
    }

    @Override
    public boolean needWaitAfTriggerDone() {
        String flashValue = mFocus.getCurrentFlashValue();
        if (FLASH_OFF_VALUE.equals(flashValue)) {
            return false;
        }
        LogHelper.d(TAG, "[needWaitAfTriggerDone] mLastResultAFState = " + mLastResultAFState
                + ",mAeState = " + mAeState);
        switch (mLastResultAFState) {
            case CaptureResult.CONTROL_AF_STATE_INACTIVE:
                if (isLastAfTriggerStillOnGoing()) {
                    return true;
                }
                return false;
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN:/**1**/
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED:/**2**/
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED:/**6**/
                if (FLASH_AUTO_VALUE.equals(flashValue) &&
                        mAeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                    return false;
                }
                return true;
            case CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN:/**3**/
                mNeedWaitActiveScanDone = true;
                return true;
            case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED:/**4**/
            case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED:/**5**/
                if (FLASH_AUTO_VALUE.equals(flashValue) &&
                        (mAeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                                mAeState == CaptureResult.CONTROL_AE_STATE_SEARCHING ||
                                mAeState == CaptureResult.CONTROL_AE_STATE_INACTIVE)) {
                    return true;
                }
                if (FLASH_ON_VALUE.equals(flashValue)) {
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    @Override
    public void doAfTriggerBeforeCapture() {
        if (isLastAfTriggerStillOnGoing()) {
            return;
        }
        sendAutoFocusTriggerCaptureRequest(true);
    }

    @Override
    public void updateFocusArea(List<Camera.Area> focusArea, List<Camera.Area> meteringArea) {
        if (focusArea != null) {
            mAFRegions = new MeteringRectangle[]{new MeteringRectangle(focusArea.get(0).rect,
                    CAMERA2_REGION_WEIGHT)};
        }
        if (meteringArea != null) {
            mAERegions = new MeteringRectangle[]{new MeteringRectangle(meteringArea.get(0).rect,
                    CAMERA2_REGION_WEIGHT)};
        }
    }

    @Override
    public boolean isFocusCanDo() {
        return CameraUtil.hasFocuser(mCameraCharacteristics);
    }

    @Override
    public String getCurrentFocusMode() {
        LogHelper.d(TAG, "getCurrentFocusMode " + convertEnumToString(mCurrentFocusMode));
        return convertEnumToString(mCurrentFocusMode);
    }

    /**
     * Get CameraCharacteristics.
     *
     * @return current CameraCharacteristics.
     */
    protected CameraCharacteristics getCameraCharacteristics() {
        return mCameraCharacteristics;
    }

    /**
     * Get CropRegion.
     *
     * @return current CropRegion.
     */
    protected Rect getCropRegion() {
        return mCropRegion;
    }

    /**
     *  Request preview capture stream with auto focus trigger cycle.
     * @param needCancelAutoFocus Whether need cancel auto focus when picture done.
     */
    private void sendAutoFocusTriggerCaptureRequest(boolean needCancelAutoFocus) {
        LogHelper.d(TAG, "[sendAutoFocusTriggerCaptureRequest] needCancelAutoFocus "
                + needCancelAutoFocus);
        // Step 1: Request single frame CONTROL_AF_TRIGGER_START.
        CaptureRequest.Builder builder = mDevice2Requester.createAndConfigRequest(
                Camera2Proxy.TEMPLATE_PREVIEW);
        if (builder == null) {
            LogHelper.w(TAG, "[sendAutoFocusTriggerCaptureRequest] builder is null");
            return;
        }
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_START);
        // Step 2: Call repeatingPreview to update mControlAFMode.
        Camera2CaptureSessionProxy sessionProxy = mDevice2Requester.getCurrentCaptureSession();
        if (sessionProxy == null) {
            LogHelper.w(TAG, "[sendAutoFocusTriggerCaptureRequest] sessionProxy is null");
            return;
        }
        try {
            if (SLOW_MOTION.equals(mFocus.getCurrentMode())) {
                LogHelper.i(TAG, "[sendAutoFocusTriggerCaptureRequest] is slow motion");
                List<CaptureRequest> captureRequests = null;
                captureRequests = sessionProxy.createHighSpeedRequestList(builder.build());
                sessionProxy.captureBurst(captureRequests, mPreviewCallback, null);
            } else {
                LogHelper.i(TAG, "[sendAutoFocusTriggerCaptureRequest] is common mode");
                sessionProxy.capture(builder.build(), mPreviewCallback, null);
            }
            mStartTime = System.currentTimeMillis();
            if (needCancelAutoFocus) {
                synchronized (mFocusQueue) {
                    if (!mFocusQueue.isEmpty()) {
                        LogHelper.d(TAG, "[sendAutoFocusTriggerCaptureRequest]  mFocusQueue " +
                                mFocusQueue.size() + " before add autoFocus");
                        mFocusQueue.clear();
                    }
                    mFocusQueue.add(AUTOFOCUS);
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        sendSettingChangeRequest();
        LogHelper.d(TAG, "[sendAutoFocusTriggerCaptureRequest]  -");
    }

    /**
     * Request preview capture stream for canceling auto focus .
     */
    private void sendAutoFocusCancelCaptureRequest() {
        LogHelper.d(TAG, "[sendAutoFocusCancelCaptureRequest]");
        // Step 1: Request single frame CONTROL_AF_TRIGGER_START.
        CaptureRequest.Builder builder = mDevice2Requester.createAndConfigRequest(
                Camera2Proxy.TEMPLATE_PREVIEW);
        if (builder == null) {
            LogHelper.w(TAG, "[sendAutoFocusTriggerCaptureRequest] builder is null");
            return;
        }
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
        // Step 2: Call repeatingPreview to update mControlAFMode.
        Camera2CaptureSessionProxy sessionProxy = mDevice2Requester.getCurrentCaptureSession();
        if (sessionProxy == null) {
            LogHelper.w(TAG, "[sendAutoFocusCancelCaptureRequest] sessionProxy is null");
            return;
        }
        try {
            if (SLOW_MOTION.equals(mFocus.getCurrentMode())) {
                LogHelper.i(TAG, "[sendAutoFocusCancelCaptureRequest] is slow motion");
                List<CaptureRequest> captureRequests = null;
                captureRequests = sessionProxy.createHighSpeedRequestList(builder.build());
                sessionProxy.captureBurst(captureRequests, null, null);
            } else {
                LogHelper.i(TAG, "[sendAutoFocusCancelCaptureRequest] is common mode");
                sessionProxy.capture(builder.build(), null, null);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        sendSettingChangeRequest();
        LogHelper.d(TAG, "[sendAutoFocusCancelCaptureRequest]  -");
    }

    private void initPlatformSupportedValues() {
        int[] availableAfModes = mCameraCharacteristics.get(
                CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (availableAfModes != null) {
            mSupportedFocusModeList = convertEnumToString(availableAfModes);
            LogHelper.d(TAG, "[initPlatformSupportedValues] availableAfModes = " +
                    Arrays.toString(availableAfModes) + ",mSupportedFocusModeList = " +
                    mSupportedFocusModeList);
            mFocus.initPlatformSupportedValues(mSupportedFocusModeList);
        }
    }

    private void initAppSupportedEntryValues() {
        mFocus.initAppSupportedEntryValues(getAppSupportedFocusModes());
    }

    private void initSettingEntryValues() {
        mFocus.initSettingEntryValues(getSettingEntryValues());
    }

    private List<String> getSettingEntryValues() {
        List<String> supportedList = new ArrayList<>();
        supportedList.addAll(mSupportedFocusModeList);
        supportedList.retainAll(getAppSupportedFocusModes());
        LogHelper.d(TAG, "[getSettingEntryValues] supportedList = " + supportedList);
        return supportedList;
    }

    private List<String> getAppSupportedFocusModes() {
        int[] availableAfModes = mCameraCharacteristics.get(
                CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        List<String> appSupportedFocusModeList = convertEnumToString(availableAfModes);
        LogHelper.d(TAG, "[getAppSupportedFocusModes] availableAfModes = "
                + Arrays.toString(availableAfModes) + ",appSupportedFocusModeList = " +
                appSupportedFocusModeList);
        return appSupportedFocusModeList;
    }

    private void initFocusMode(List<String> modes) {
        LogHelper.d(TAG, "[initFocusMode] + ");
        if (modes == null || modes.isEmpty()) {
            return;
        }
        if (modes.indexOf(convertEnumToString(FocusEnum
                .CONTINUOUS_PICTURE.getValue())) > 0) {
            mCurrentFocusMode = FocusEnum
                    .CONTINUOUS_PICTURE.getValue();
        } else if (modes.indexOf(convertEnumToString(FocusEnum
                .AUTO.getValue())) > 0) {
            mCurrentFocusMode = FocusEnum
                    .AUTO.getValue();
        } else {
            mCurrentFocusMode = convertStringToEnum(getSettingEntryValues().get(0));
        }
        mFocus.setValue(convertEnumToString(mCurrentFocusMode));
        LogHelper.d(TAG, "[initFocusMode] - mCurrentFocusMode " + mCurrentFocusMode);
    }

    /**
     * Adds current regions to CaptureRequest and base AF mode + AF_TRIGGER_IDLE.
     *
     * @param builder Build for the CaptureRequest
     */
    private void addBaselineCaptureKeysToRequest(CaptureRequest.Builder builder) {
        if (sIsLogAeAfRegion) {
            LogHelper.d(TAG, "[addBaselineCaptureKeysToRequest] mAERegions[0] = " + mAERegions[0]);
            LogHelper.d(TAG, "[addBaselineCaptureKeysToRequest] mAFRegions[0] = " + mAFRegions[0]);
        }
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, mAFRegions);
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, mAERegions);
        builder.set(CaptureRequest.CONTROL_AF_MODE, mCurrentFocusMode);
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
    }

    /**
     * This method takes appropriate action if camera2 AF state changes.
     * <ol>
     * <li>Reports changes in camera2 AF state to OneCamera.FocusStateListener.</li>
     * <li>Take picture after AF scan if mTakePictureWhenLensIsStopped true.</li>
     * </ol>
     */
    private void autofocusStateChangeDispatcher(CaptureResult result) {
        long currentFrameNumber = result.getFrameNumber();
        if (currentFrameNumber < mLastControlAfStateFrameNumber ||
                result.get(CaptureResult.CONTROL_AF_STATE) == null) {
            LogHelper.w(TAG, "[autofocusStateChangeDispatcher] frame number, last:current " +
                    mLastControlAfStateFrameNumber +
                    ":" + currentFrameNumber + " afState:" +
                    result.get(CaptureResult.CONTROL_AF_STATE));
            return;
        }
        mLastControlAfStateFrameNumber = result.getFrameNumber();
        int resultAFState = result.get(CaptureResult.CONTROL_AF_STATE);
        if (mLastResultAFState != resultAFState) {
            notifyFocusStateChanged(resultAFState, mLastControlAfStateFrameNumber);
            LogHelper.d(TAG, "[autofocusStateChangeDispatcher] mLastResultAFState " +
                    mLastResultAFState + ",resultAFState " + resultAFState);
        }
        mLastResultAFState = resultAFState;
    }

    private void notifyFocusStateChanged(int afState, long frameNumber) {
        AutoFocusState state = AutoFocusState.INACTIVE;
        switch (afState) {
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN:/**1**/
                state = AutoFocusState.PASSIVE_SCAN;
                break;
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED:/**2**/
                state = AutoFocusState.PASSIVE_FOCUSED;
                break;
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED:/**6**/
                state = AutoFocusState.PASSIVE_UNFOCUSED;
                break;
            case CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN:/**3**/
                state = AutoFocusState.ACTIVE_SCAN;
                break;
            case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED:/**4**/
                LogHelper.i(TAG, "[notifyFocusStateChanged] autoFocus time " +
                        (System.currentTimeMillis() - mStartTime));
                state = AutoFocusState.ACTIVE_FOCUSED;
                break;
            case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED:/**5**/
                LogHelper.i(TAG, "[notifyFocusStateChanged] autoFocus time  " +
                        (System.currentTimeMillis() - mStartTime));
                state = AutoFocusState.ACTIVE_UNFOCUSED;
                break;
            default:
                break;
        }
        if (mDisableUpdateFocusState && state == AutoFocusState.PASSIVE_SCAN) {
            return;
        }

        String flashValue = mFocus.getCurrentFlashValue();
        if (mNeedWaitActiveScanDone && (!FLASH_OFF_VALUE.equals(flashValue)) &&
                (afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) {
            LogHelper.d(TAG, "[notifyFocusStateChanged]  need trigger AF again");
            doAfTriggerBeforeCapture();
            mNeedWaitActiveScanDone = false;
            return;
        }

        if (isActiveFocusDone(afState)) {
            synchronized (mFocusQueue) {
                if (!mFocusQueue.isEmpty() && AUTOFOCUS.equals(mFocusQueue.peek())) {
                    LogHelper.d(TAG, "[notifyFocusStateChanged] mFocusQueue " + mFocusQueue.size() +
                            " before add cancelAutoFocus");
                    mFocusQueue.clear();
                    mFocusQueue.add(CANCEL_AUTOFOCUS);
                }
            }
            synchronized (mLock) {
                if (mFocusStateListener != null && frameNumber > mLastAfTriggerFrameNumber) {
                    mFocusStateListener.onFocusStatusUpdate(state, frameNumber);
                } else {
                    LogHelper.w(TAG, "[notifyFocusStateChanged] last AF trigger start at " +
                            mLastAfTriggerFrameNumber + ",but done at " + frameNumber);
                }
            }
            return;
        }

        synchronized (mLock) {
            if (mFocusStateListener != null) {
                mFocusStateListener.onFocusStatusUpdate(state, frameNumber);
            }
        }
    }
    private void updateAeState(CaptureRequest request, TotalCaptureResult result) {
        Integer aeState = result.get(TotalCaptureResult.CONTROL_AE_STATE);
        if (request == null || result == null || aeState == null) {
            return;
        }
        mAeState = aeState;
    }

    private boolean isLastAfTriggerStillOnGoing() {
        //do not trigger AF when the last AF trigger still ongoing
        if (!mFocusQueue.isEmpty() && AUTOFOCUS.equals(mFocusQueue.peek())) {
            LogHelper.w(TAG, "[isLastAfTriggerStillOnGoing] last autoFocus still in running");
            return true;
        }
        //do not trigger AF when the last cancelAutoFocus not donging
        if (!mFocusQueue.isEmpty() && CANCEL_AUTOFOCUS.equals(mFocusQueue.peek())) {
            LogHelper.w(TAG, "[isLastAfTriggerStillOnGoing] last cancelAutoFocus still in running");
            return true;
        }
        return false;
    }

    private List<String> convertEnumToString(int[] enumIndexs) {
        FocusEnum[] modes = FocusEnum.values();
        List<String> names = new ArrayList<>(enumIndexs.length);
        for (int i = 0; i < enumIndexs.length; i++) {
            int enumIndex = enumIndexs[i];
            for (FocusEnum mode : modes) {
                if (mode.getValue() == enumIndex) {
                    String name = mode.getName().replace('_', '-').toLowerCase(Locale.ENGLISH);
                    names.add(name);
                    break;
                }
            }
        }
        return names;
    }

    private String convertEnumToString(int enumIndex) {
        FocusEnum[] modes = FocusEnum.values();
        String name = null;
        for (FocusEnum mode : modes) {
            if (mode.getValue() == enumIndex) {
                name = mode.getName().replace('_', '-').toLowerCase(Locale.ENGLISH);
                break;
            }
        }
        return name;
    }

    private int convertStringToEnum(String value) {
        int enumIndex = 0;
        FocusEnum[] modes = FocusEnum.values();
        for (FocusEnum mode : modes) {
            String modeName = mode.getName().replace('_', '-').toLowerCase(Locale.ENGLISH);
            if (modeName.equalsIgnoreCase(value)) {
                enumIndex = mode.getValue();
            }
        }
        return enumIndex;
    }

    private CameraCaptureSession.CaptureCallback mPreviewCallback
            = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request,
                                     long timestamp, long frameNumber) {
            super.onCaptureStarted(session, request, timestamp, frameNumber);
            if (CaptureRequest.CONTROL_AF_TRIGGER_START ==
                    request.get(CaptureRequest.CONTROL_AF_TRIGGER)) {
                mModeHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mLastAfTriggerFrameNumber = frameNumber;
                        LogHelper.d(TAG, "[onCaptureStarted] mLastAfTriggerFrameNumber " +
                                mLastAfTriggerFrameNumber);
                    }
                });
            }
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                       TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            if (result != null) {
                Rect rect = result.get(CaptureResult.SCALER_CROP_REGION);
                if (rect != null) {
                    mCropRegion = rect;
                }
            }
            updateAeState(request, result);
            autofocusStateChangeDispatcher(result);
            if (!CameraUtil.isStillCaptureTemplate(result)) {
                return;
            }
            synchronized (mFocusQueue) {
                LogHelper.d(TAG, "[onCaptureCompleted] picture done");
                if (!mFocusQueue.isEmpty() && CANCEL_AUTOFOCUS.equals(mFocusQueue.peek())) {
                    LogHelper.d(TAG, "[onCaptureCompleted] mFocusQueue " + mFocusQueue.size() +
                            " do cancelAutoFocus");
                    mFocusQueue.clear();
                    cancelAutoFocus();
                    mFocus.resetTouchFocusWhenCaptureDone();
                }

            }
        }
    };

    private boolean isActiveFocusDone(int state) {
        boolean isActiveFocusDone = (state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
                state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED);
        return isActiveFocusDone;
    }

    /**
     * Linear interpolation between a and b by the fraction t. t = 0 --> a, t =
     * 1 --> b.
     */
    private static float lerp(float a, float b, float t) {
        return a + t * (b - a);
    }
}
