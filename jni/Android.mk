LOCAL_PATH := $(call my-dir)

OPENCV_SDK := $(wildcard $(LOCAL_PATH)/../libs/OpenCV-*-android-sdk/sdk)

# CI2CV face analysis SDK
include $(CLEAR_VARS)

OPENCV_INSTALL_MODULES := on
include $(OPENCV_SDK)/native/jni/OpenCV.mk

LOCAL_MODULE := ci2cv

LOCAL_CPP_FEATURES += rtti exceptions
LOCAL_C_INCLUDES   += $(LOCAL_PATH)/ci2cv

CI2CV_SRC_FILES := $(wildcard jni/ci2cv/*/*.cpp)
LOCAL_SRC_FILES := $(CI2CV_SRC_FILES:jni/%=%)

include $(BUILD_STATIC_LIBRARY)

# FFmpeg
FFMPEG_CFLAGS   := --std=c99 -march=armv5te -Dstrtod=avpriv_strtod -DHAVE_AV_CONFIG_H
FFMPEG_INCLUDES := $(LOCAL_PATH)/ffmpeg $(LOCAL_PATH)/include/ffmpeg

# FFmpeg - libavutil
include $(CLEAR_VARS)

LOCAL_MODULE := avutil

LOCAL_ARM_MODE   := arm
LOCAL_CFLAGS     := $(FFMPEG_CFLAGS)
LOCAL_C_INCLUDES := $(FFMPEG_INCLUDES)

AVUTIL_SRC_FILES := $(wildcard jni/ffmpeg/libavutil/*.c jni/ffmpeg/libavutil/arm/*.c jni/ffmpeg/libavutil/arm/*.S \
	jni/ffmpeg/compat/strtod.c)
AVUTIL_EXCLUDE   := integer.c opencl.c opencl_internal.c pca.c softfloat.c utf8.c asm.S

AVUTIL_SRC_FILES := $(filter-out $(addprefix %/,$(AVUTIL_EXCLUDE)),$(AVUTIL_SRC_FILES))
LOCAL_SRC_FILES  := $(AVUTIL_SRC_FILES:jni/%=%)

include $(BUILD_SHARED_LIBRARY)

# FFmpeg - libavcodec
include $(CLEAR_VARS)

LOCAL_MODULE := avcodec

LOCAL_ARM_MODE   := arm
LOCAL_CFLAGS     := $(FFMPEG_CFLAGS)
LOCAL_C_INCLUDES := $(FFMPEG_INCLUDES)
LOCAL_LDFLAGS    := -lz

AVCODEC_SRC_FILES := $(wildcard jni/ffmpeg/libavcodec/*.c jni/ffmpeg/libavcodec/arm/*.c jni/ffmpeg/libavcodec/arm/*.S)
AVCODEC_EXCLUDE   := $(wildcard jni/ffmpeg/libavcodec/*_tablegen.c jni/ffmpeg/libavcodec/*_template.c \
	jni/ffmpeg/libavcodec/*-test.c jni/ffmpeg/libavcodec/dxva2*.c jni/ffmpeg/libavcodec/lib*.c jni/ffmpeg/libavcodec/vaapi*.c \
	jni/ffmpeg/libavcodec/vda*.c jni/ffmpeg/libavcodec/vdpau*.c) %/aacpsdata.c %/crystalhd.c %/dctref.c %/file_open.c \
	%/mpegvideo_xvmc.c %/neon.S %/neontest.c

AVCODEC_SRC_FILES := $(filter-out $(AVCODEC_EXCLUDE),$(AVCODEC_SRC_FILES))
LOCAL_SRC_FILES   := $(AVCODEC_SRC_FILES:jni/%=%)

LOCAL_SHARED_LIBRARIES := avutil

include $(BUILD_SHARED_LIBRARY)

# FFmpeg - libavformat
include $(CLEAR_VARS)

LOCAL_MODULE := avformat

LOCAL_CFLAGS     := $(FFMPEG_CFLAGS)
LOCAL_C_INCLUDES := $(FFMPEG_INCLUDES)
LOCAL_LDFLAGS    := -lz

AVFORMAT_SRC_FILES := $(wildcard jni/ffmpeg/libavformat/*.c)
AVFORMAT_EXCLUDE   := avisynth.c bluray.c file_open.c libgme.c libmodplug.c libnut.c libquvi.c librtmp.c libssh.c noproxy-test.c \
	rtmpcrypt.c rtmpdh.c sctp.c seek-test.c tls.c url-test.c

AVFORMAT_SRC_FILES := $(filter-out $(addprefix %/,$(AVFORMAT_EXCLUDE)),$(AVFORMAT_SRC_FILES))
LOCAL_SRC_FILES    := $(AVFORMAT_SRC_FILES:jni/%=%)

LOCAL_SHARED_LIBRARIES := avcodec avutil

include $(BUILD_SHARED_LIBRARY)

# FFmpeg - libswscale
include $(CLEAR_VARS)

LOCAL_MODULE := swscale

LOCAL_CFLAGS     := $(FFMPEG_CFLAGS)
LOCAL_C_INCLUDES := $(FFMPEG_INCLUDES)

SWSCALE_SRC_FILES := $(wildcard jni/ffmpeg/libswscale/*.c)
SWSCALE_EXCLUDE   := $(wildcard jni/ffmpeg/libswscale/*_template.c jni/ffmpeg/libswscale/*-test.c)

SWSCALE_SRC_FILES := $(filter-out $(SWSCALE_EXCLUDE),$(SWSCALE_SRC_FILES))
LOCAL_SRC_FILES   := $(SWSCALE_SRC_FILES:jni/%=%)

LOCAL_SHARED_LIBRARIES := avutil

include $(BUILD_SHARED_LIBRARY)

# Main image processing code
include $(CLEAR_VARS)

OPENCV_INSTALL_MODULES := on
include $(OPENCV_SDK)/native/jni/OpenCV.mk

LOCAL_MODULE := mobileface

LOCAL_CFLAGS     += --std=c++11 -Wall -Wno-parentheses -Werror
LOCAL_C_INCLUDES += $(LOCAL_PATH)/ci2cv $(LOCAL_PATH)/ffmpeg $(LOCAL_PATH)/include/ffmpeg
LOCAL_LDLIBS     += -ljnigraphics -llog

LOCAL_SRC_FILES := visual_view.cpp

LOCAL_SHARED_LIBRARIES := avcodec avformat avutil swscale
LOCAL_STATIC_LIBRARIES := ci2cv

include $(BUILD_SHARED_LIBRARY)

