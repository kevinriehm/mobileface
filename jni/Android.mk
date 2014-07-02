LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

OPENCV_SDK := $(wildcard $(LOCAL_PATH)/../libs/OpenCV-*-android-sdk/sdk)

include $(OPENCV_SDK)/native/jni/OpenCV.mk

LOCAL_MODULE := mobileface

LOCAL_CFLAGS       += -Wall
LOCAL_CPP_FEATURES += exceptions
LOCAL_LDLIBS       += -ljnigraphics -llog

LOCAL_SRC_FILES := visual_view.cpp

include $(BUILD_SHARED_LIBRARY)

