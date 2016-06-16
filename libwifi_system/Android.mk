# Copyright (C) 2016 The Android Open Source Project
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

# Device independent wifi system logic.
# ============================================================
include $(CLEAR_VARS)
LOCAL_MODULE := libwifi-system
LOCAL_CFLAGS := \
    -Wall \
    -Werror \
    -Wextra \
    -Winit-self \
    -Wno-unused-function \
    -Wno-unused-parameter \
    -Wshadow \
    -Wunused-variable \
    -Wwrite-strings
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include
LOCAL_EXPORT_C_INCLUDE_DIRS := $(LOCAL_PATH)/include
LOCAL_SHARED_LIBRARIES := \
    libcutils \
    liblog \
    libnetutils \
    libnl

# Tolerate certain emulators which apparently don't have supplicant installed.
ifdef WPA_SUPPLICANT_VERSION
LOCAL_CFLAGS += -DLIBWPA_CLIENT_EXISTS
LOCAL_SHARED_LIBRARIES += libwpa_client
endif

LOCAL_SRC_FILES := wifi.c
include $(BUILD_SHARED_LIBRARY)

endif
