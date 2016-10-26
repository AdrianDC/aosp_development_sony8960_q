# Copyright (C) 2015 The Android Open Source Project
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

LOCAL_PATH:= $(call my-dir)

# Make mock HAL library
# ============================================================

include $(CLEAR_VARS)

LOCAL_REQUIRED_MODULES :=

LOCAL_CFLAGS += -Wno-unused-parameter -Wall -Werror

LOCAL_C_INCLUDES += \
	$(JNI_H_INCLUDE) \
	$(LOCAL_PATH)/../../service/jni \
	$(call include-path-for, libhardware)/hardware \
	$(call include-path-for, libhardware_legacy)/hardware_legacy \
	packages/apps/Test/connectivity/sl4n/rapidjson/include \
	libcore/include

LOCAL_SRC_FILES := \
	jni/wifi_hal_mock.cpp

ifdef INCLUDE_AWARE_FEATURE
LOCAL_SRC_FILES += \
	jni/wifi_aware_hal_mock.cpp
endif

LOCAL_MODULE := libwifi-hal-mock

LOCAL_STATIC_LIBRARIES += libwifi-hal
LOCAL_SHARED_LIBRARIES += \
	libnativehelper \
	libcutils \
	libutils \
	libhardware \
	libhardware_legacy \
	libnl \
	libdl \
	libwifi-service

include $(BUILD_SHARED_LIBRARY)

# Make test APK
# ============================================================
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

RESOURCE_FILES := $(call all-named-files-under, R.java, $(intermediates.COMMON))

LOCAL_SRC_FILES := $(call all-subdir-java-files) \
	$RESOURCE_FILES

ifndef INCLUDE_AWARE_FEATURE
LOCAL_SRC_FILES := $(filter-out $(call all-java-files-under, \
          src/com/android/server/wifi/aware),$(LOCAL_SRC_FILES))
endif

LOCAL_STATIC_JAVA_LIBRARIES := \
	mockito-target \
	android-support-test \
	wifi-service \
	services

LOCAL_JAVA_LIBRARIES := android.test.runner \
	mockito-target \
	android-support-test \
	wifi-service \
	services

LOCAL_JNI_SHARED_LIBRARIES := \
	libwifi-service \
	libc++ \
	libLLVM \
	libutils \
	libunwind \
	libhardware_legacy \
	libbase \
	libhardware \
	libnl \
	libcutils \
	libnetutils \
	libbacktrace \
	libnativehelper \

ifdef WPA_SUPPLICANT_VERSION
LOCAL_JNI_SHARED_LIBRARIES := $(LOCAL_JNI_SHARED_LIBRARIES) \
	libwpa_client
endif

LOCAL_PACKAGE_NAME := FrameworksWifiTests
LOCAL_JNI_SHARED_LIBRARIES += libwifi-hal-mock

include $(BUILD_PACKAGE)
