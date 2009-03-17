LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := samples

LOCAL_SRC_FILES := $(call all-subdir-java-files) \

LOCAL_PACKAGE_NAME := ImPluginDemo

LOCAL_JAVA_LIBRARIES := com.android.im.plugin

include $(BUILD_PACKAGE)
