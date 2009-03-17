LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files) \
			src/com/android/im/IChatSession.aidl \
			src/com/android/im/IChatSessionListener.aidl \
			src/com/android/im/IInvitationListener.aidl \
			src/com/android/im/IChatSessionManager.aidl \
			src/com/android/im/IConnectionListener.aidl \
			src/com/android/im/IContactList.aidl \
			src/com/android/im/IContactListListener.aidl \
			src/com/android/im/IContactListManager.aidl \
			src/com/android/im/IImConnection.aidl \
			src/com/android/im/IChatListener.aidl \
			src/com/android/im/IRemoteImService.aidl \
			src/com/android/im/ISubscriptionListener.aidl \
			src/com/android/im/IConnectionCreationListener.aidl \

# Filter out the plugin and samples when build IM.apk
LOCAL_SRC_FILES := $(filter-out \
                       plugin/% samples/% \
                       ,$(LOCAL_SRC_FILES))

LOCAL_PACKAGE_NAME := IM

# TODO: Remove dependency of application on the test runner (android.test.runner)
# library.
LOCAL_JAVA_LIBRARIES := android.test.runner \
                        com.android.im.plugin \
#                        com.android.providers.im.plugin

# LOCAL_REQUIRED_MODULES must go before BUILD_PACKAGE
LOCAL_REQUIRED_MODULES := libwbxml libwbxml_jni ImProvider

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
