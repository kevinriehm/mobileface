LOCAL_PATH := $(call my-dir)

OPENCV_SDK := $(wildcard $(LOCAL_PATH)/../libs/OpenCV-*-android-sdk/sdk)

# CI2CV face analysis SDK
include $(CLEAR_VARS)

include $(OPENCV_SDK)/native/jni/OpenCV.mk

LOCAL_MODULE := ci2cv

LOCAL_CPP_FEATURES += rtti exceptions
LOCAL_C_INCLUDES   += $(LOCAL_PATH)/ci2cv

CI2CV_SRC_FILES := $(wildcard jni/ci2cv/*/*.cpp)
LOCAL_SRC_FILES := $(CI2CV_SRC_FILES:jni/%=%)

include $(BUILD_STATIC_LIBRARY)

# Main image processing code
include $(CLEAR_VARS)

include $(OPENCV_SDK)/native/jni/OpenCV.mk

LOCAL_MODULE := mobileface

LOCAL_CFLAGS     += -Wall -Wno-parentheses -Werror
LOCAL_C_INCLUDES += $(LOCAL_PATH)/ci2cv
LOCAL_LDLIBS     += -ljnigraphics -llog

LOCAL_SRC_FILES := visual_view.cpp

LOCAL_STATIC_LIBRARIES := ci2cv

include $(BUILD_SHARED_LIBRARY)

