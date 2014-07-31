#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <ostream>
#include <string>

#include <android/bitmap.h>
#include <android/log.h>

#include <avatar/myAvatar.hpp>

#include <opencv2/opencv.hpp>

#include <jni.h>

#define TAG "MobileFace-ExpressionView"
#define LOGE(msg) __android_log_write(ANDROID_LOG_ERROR,TAG,msg)
#define LOGI(msg) __android_log_write(ANDROID_LOG_INFO,TAG,msg)

template <typename T>
std::string to_string(T val) {
	std::ostringstream os;
	os << val;
	return os.str();
}

AVATAR::myAvatar *get_avatar(JNIEnv *jenv, jstring path) {
	const char *spath;
	AVATAR::myAvatar *avatar;

	spath = jenv->GetStringUTFChars(path,NULL);
	avatar = (AVATAR::myAvatar *) AVATAR::LoadAvatar(spath);
	jenv->ReleaseStringUTFChars(path,spath);

	return avatar;
}

extern "C" jboolean Java_com_kevinriehm_mobileface_ExpressionView_getAvatarSize(JNIEnv *jenv, jobject jthis, jstring path, jintArray size) {
	std::unique_ptr<AVATAR::myAvatar> avatar(get_avatar(jenv,path));

	if(!avatar.get())
		return false;

	jint sizebuf[] = {avatar->_warp.Width(), avatar->_warp.Height()};
	jenv->SetIntArrayRegion(size,0,2,sizebuf);

	return true;
}

uint8_t clamp8(double x) {
	return x < 0 ? 0 : x < 0xff ? (uint8_t) floor(x + 0.5) : 0xff;
}

extern "C" jboolean Java_com_kevinriehm_mobileface_ExpressionView_getAvatarImage(JNIEnv *jenv, jobject jthis, jstring path, jobject bitmap, jobject uvs) {
	int w, h;
	jfloat uv[2];
	double *data;
	int xmin, ymin;
	jfloat *uvdata;
	uint8_t rgba[4];
	uint8_t *pixels;
	cv::MatIterator_<uchar> mask;
	int index, npoints, planestride;
	cv::MatIterator_<double> rdata, gdata, bdata;
	std::unique_ptr<AVATAR::myAvatar> avatar(get_avatar(jenv,path));

	if(!avatar.get())
		return false;

	// Copy over the avatar image
	data = (double *) avatar->_textr[2].data;

	w = avatar->_warp.Width();
	h = avatar->_warp.Height();

	planestride = avatar->_warp._nPix;

	rdata = avatar->_textr[2](cv::Rect(0,2*planestride,1,planestride)).begin<double>();
	gdata = avatar->_textr[2](cv::Rect(0,  planestride,1,planestride)).begin<double>();
	bdata = avatar->_textr[2](cv::Rect(0,0            ,1,planestride)).begin<double>();

	mask = avatar->_warp._mask.begin<uchar>();

	AndroidBitmap_lockPixels(jenv,bitmap,(void **) &pixels);

	index = 0;
	for(int y = 0; y < h; y++) {
		for(int x = 0; x < w; x++) {
			if(*mask++) {
				rgba[0] = clamp8(*rdata++);
				rgba[1] = clamp8(*gdata++);
				rgba[2] = clamp8(*bdata++);
			} else rgba[0] = rgba[1] = rgba[2] = 0xff;

			rgba[4] = 0xff;

			memcpy(pixels + index++*sizeof rgba,rgba,sizeof rgba);
		}
	}

	AndroidBitmap_unlockPixels(jenv,bitmap);

	data = (double *) avatar->_shapes[2].data;

	xmin = avatar->_warp._xmin;
	ymin = avatar->_warp._ymin;

	npoints = avatar->_shapes[2].rows/3;
	planestride = npoints;

	uvdata = (jfloat *) jenv->GetDirectBufferAddress(uvs);

	for(int i = 0; i < npoints; i++) {
		uvdata[2*i    ] = (jfloat) ((data[              i] - xmin)/w);
		uvdata[2*i + 1] = (jfloat) ((data[planestride + i] - ymin)/h);
	}

	return true;
}

