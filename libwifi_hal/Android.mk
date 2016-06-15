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

# A fallback "vendor" HAL library.
# Don't link this, link libwifi-hal.
# ============================================================
include $(CLEAR_VARS)
LOCAL_MODULE := libwifi-hal-fallback
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
LOCAL_SRC_FILES := wifi_hal_fallback.cpp
LOCAL_C_INCLUDES := $(call include-path-for, libhardware_legacy)
include $(BUILD_STATIC_LIBRARY)

# Pick a vendor provided HAL implementation library.
# ============================================================
LIB_WIFI_HAL := libwifi-hal-fallback
ifeq ($(BOARD_WLAN_DEVICE), bcmdhd)
  LIB_WIFI_HAL := libwifi-hal-bcm
else ifeq ($(BOARD_WLAN_DEVICE), qcwcn)
  LIB_WIFI_HAL := libwifi-hal-qcom
else ifeq ($(BOARD_WLAN_DEVICE), mrvl)
  # this is commented because none of the nexus devices
  # that sport Marvell's wifi have support for HAL
  # LIB_WIFI_HAL := libwifi-hal-mrvl
else ifeq ($(BOARD_WLAN_DEVICE), MediaTek)
  # support MTK WIFI HAL
  LIB_WIFI_HAL := libwifi-hal-mt66xx
endif

# The WiFi HAL that you should be linking.
# ============================================================
include $(CLEAR_VARS)
LOCAL_MODULE := libwifi-hal
LOCAL_EXPORT_C_INCLUDE_DIRS := $(call include-path-for, libhardware_legacy)
LOCAL_SHARED_LIBRARIES := \
    liblog \
    libnl \
    libutils
LOCAL_WHOLE_STATIC_LIBRARIES := $(LIB_WIFI_HAL)
include $(BUILD_SHARED_LIBRARY)

endif
