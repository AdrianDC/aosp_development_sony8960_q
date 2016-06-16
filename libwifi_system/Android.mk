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

# TODO(wiley): Move this configuration to system properties and additional HAL
#              interfaces.
wifi_system_cflags :=
wifi_system_shared_libraries :=
ifdef WIFI_DRIVER_MODULE_PATH
wifi_system_cflags += -DWIFI_DRIVER_MODULE_PATH=\"$(WIFI_DRIVER_MODULE_PATH)\"
endif
ifdef WIFI_DRIVER_MODULE_ARG
wifi_system_cflags += -DWIFI_DRIVER_MODULE_ARG=\"$(WIFI_DRIVER_MODULE_ARG)\"
endif
ifdef WIFI_DRIVER_MODULE_NAME
wifi_system_cflags += -DWIFI_DRIVER_MODULE_NAME=\"$(WIFI_DRIVER_MODULE_NAME)\"
endif
ifdef WIFI_DRIVER_FW_PATH_STA
wifi_system_cflags += -DWIFI_DRIVER_FW_PATH_STA=\"$(WIFI_DRIVER_FW_PATH_STA)\"
endif
ifdef WIFI_DRIVER_FW_PATH_AP
wifi_system_cflags += -DWIFI_DRIVER_FW_PATH_AP=\"$(WIFI_DRIVER_FW_PATH_AP)\"
endif
ifdef WIFI_DRIVER_FW_PATH_P2P
wifi_system_cflags += -DWIFI_DRIVER_FW_PATH_P2P=\"$(WIFI_DRIVER_FW_PATH_P2P)\"
endif
ifdef WIFI_DRIVER_FW_PATH_PARAM
wifi_system_cflags += -DWIFI_DRIVER_FW_PATH_PARAM=\"$(WIFI_DRIVER_FW_PATH_PARAM)\"
endif

ifdef WIFI_DRIVER_STATE_CTRL_PARAM
wifi_system_cflags += -DWIFI_DRIVER_STATE_CTRL_PARAM=\"$(WIFI_DRIVER_STATE_CTRL_PARAM)\"
endif
ifdef WIFI_DRIVER_STATE_ON
wifi_system_cflags += -DWIFI_DRIVER_STATE_ON=\"$(WIFI_DRIVER_STATE_ON)\"
endif
ifdef WIFI_DRIVER_STATE_OFF
wifi_system_cflags += -DWIFI_DRIVER_STATE_OFF=\"$(WIFI_DRIVER_STATE_OFF)\"
endif

ifdef WPA_SUPPLICANT_VERSION
wifi_system_cflags += -DLIBWPA_CLIENT_EXISTS
wifi_system_shared_libraries += libwpa_client
endif


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
    -Wwrite-strings \
    $(wifi_system_cflags)
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include
LOCAL_EXPORT_C_INCLUDE_DIRS := $(LOCAL_PATH)/include
LOCAL_SHARED_LIBRARIES := \
    libcutils \
    liblog \
    libnetutils \
    libnl \
    $(wifi_system_shared_libraries)
LOCAL_SRC_FILES := wifi.c
include $(BUILD_SHARED_LIBRARY)

endif
