# Copyright (C) 2011 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

LOCAL_PATH := $(call my-dir)

ifneq ($(TARGET_BUILD_PDK), true)

# This is the HAL stub library.  We initialize the HAL function table
# with functions from here so that we have reasonable "unimplemented"
# fallback behavior when a behavior isn't implemented by a vendor.
# ============================================================
include $(CLEAR_VARS)
LOCAL_MODULE := libwifi-hal-stub
LOCAL_CFLAGS := \
    -Wall \
    -Werror \
    -Wextra \
    -Wno-unused-parameter \
    -Wno-unused-function \
    -Wunused-variable \
    -Winit-self \
    -Wwrite-strings \
    -Wshadow

LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/jni \
    $(call include-path-for, libhardware_legacy)
LOCAL_EXPORT_C_INCLUDE_DIRS := $(LOCAL_C_INCLUDES)
LOCAL_SHARED_LIBRARIES := libnativehelper
LOCAL_SRC_FILES := lib/wifi_hal_stub.cpp
include $(BUILD_STATIC_LIBRARY)

# Make the JNI part
# ============================================================
include $(CLEAR_VARS)

LOCAL_CFLAGS += -Wall -Werror -Wextra -Wno-unused-parameter -Wno-unused-function \
                -Wunused-variable -Winit-self -Wwrite-strings -Wshadow

LOCAL_C_INCLUDES += \
	$(JNI_H_INCLUDE) \
	libcore/include

LOCAL_SHARED_LIBRARIES += \
	liblog \
	libnativehelper \
	libcutils \
	libutils \
	libdl \
	libwifi-hal \
	libwifi-system

LOCAL_STATIC_LIBRARIES := libwifi-hal-stub

LOCAL_SRC_FILES := \
	jni/com_android_server_wifi_WifiNative.cpp \
	jni/jni_helper.cpp

ifeq ($(BOARD_HAS_NAN), true)
LOCAL_SRC_FILES += \
	jni/com_android_server_wifi_nan_WifiNanNative.cpp
endif

LOCAL_MODULE := libwifi-service

include $(BUILD_SHARED_LIBRARY)

# Build the java code
# ============================================================

include $(CLEAR_VARS)

LOCAL_AIDL_INCLUDES := $(LOCAL_PATH)/java
LOCAL_SRC_FILES := $(call all-java-files-under, java) \
	$(call all-Iaidl-files-under, java) \
	$(call all-logtags-files-under, java) \
	$(call all-proto-files-under, proto)

ifneq ($(BOARD_HAS_NAN), true)
LOCAL_SRC_FILES := $(filter-out $(call all-java-files-under, \
          java/com/android/server/wifi/nan),$(LOCAL_SRC_FILES))
endif

LOCAL_JAVA_LIBRARIES := bouncycastle conscrypt services
LOCAL_REQUIRED_MODULES := services
LOCAL_MODULE_TAGS :=
LOCAL_MODULE := wifi-service
LOCAL_PROTOC_OPTIMIZE_TYPE := nano

include $(BUILD_JAVA_LIBRARY)

endif
