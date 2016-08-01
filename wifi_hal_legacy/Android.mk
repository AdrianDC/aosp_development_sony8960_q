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

include $(CLEAR_VARS)
LOCAL_MODULE := wifi_hal_legacy
LOCAL_MODULE_RELATIVE_PATH := hw
LOCAL_CPPFLAGS := -std=c++11 -Wall -Wno-unused-parameter -Werror -Wextra
LOCAL_SRC_FILES := \
    main.cpp \
    failure_reason_util.cpp \
    wifi_hal_service.cpp
LOCAL_SHARED_LIBRARIES := \
    android.hardware.wifi@1.0 \
    libbase \
    libcutils \
    libhidl \
    libhwbinder \
    liblog \
    libnl \
    libutils
LOCAL_WHOLE_STATIC_LIBRARIES := $(LIB_WIFI_HAL)
# TODO reenable once the service will start successfully
# LOCAL_INIT_RC := wifi_hal_legacy.rc
include $(BUILD_EXECUTABLE)
