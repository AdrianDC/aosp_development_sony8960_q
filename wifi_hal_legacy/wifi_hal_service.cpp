/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "wifi_hal_service.h"

#include <android-base/logging.h>

#include "failure_reason_util.h"

using ::android::hardware::wifi::V1_0::CommandFailureReason;

namespace {
class FunctionMessageHandler : public android::MessageHandler {
 public:
  explicit FunctionMessageHandler(const std::function<void()>& callback)
      : callback_(callback) {
  }

  ~FunctionMessageHandler() override = default;

  virtual void handleMessage(const android::Message& /*message*/) {
    callback_();
  }

 private:
  const std::function<void()> callback_;

  DISALLOW_COPY_AND_ASSIGN(FunctionMessageHandler);
};
}

namespace android {
namespace hardware {
namespace wifi {

WifiHalService::WifiHalService(sp<Looper>& looper) :
    looper_(looper), state_(State::STOPPED) {
  CHECK_EQ(init_wifi_vendor_hal_func_table(&hal_func_table_), WIFI_SUCCESS)
      << "Failed to initialize hal func table";
}

Return<void> WifiHalService::registerEventCallback(
    const sp<V1_0::IWifiEventCallback>& callback) {
  callbacks_.insert(callback);
  return Void();
}

Return<bool> WifiHalService::isStarted() {
  return state_ != State::STOPPED;
}

Return<void> WifiHalService::start() {
  if (state_ == State::STARTED) {
    for (auto& callback : callbacks_) {
      callback->onStart();
    }
    return Void();
  } else if (state_ == State::STOPPING) {
    for (auto& callback : callbacks_) {
      callback->onStartFailure(CreateFailureReason(
          CommandFailureReason::NOT_AVAILABLE, "HAL is stopping"));
    }
    return Void();
  }

  LOG(INFO) << "Initializing HAL";
  wifi_error status = hal_func_table_.wifi_initialize(&hal_handle_);
  if (status != WIFI_SUCCESS) {
    LOG(ERROR) << "Failed to initialize Wifi HAL";
    for (auto& callback : callbacks_) {
      callback->onStartFailure(CreateFailureReasonLegacyError(
          status, "Failed to initialize HAL"));
    }
    return Void();
  }

  event_loop_thread_ = std::thread(&WifiHalService::DoHalEventLoop, this);
  state_ = State::STARTED;
  for (auto& callback : callbacks_) {
    callback->onStart();
  }
  return Void();
}

void NoopHalCleanupHandler(wifi_handle) {}

Return<void> WifiHalService::stop() {
  if (state_ == State::STOPPED) {
    for (auto& callback : callbacks_) {
      callback->onStop();
    }
    return Void();
  } else if (state_ == State::STOPPING) {
    return Void();
  }

  LOG(INFO) << "Cleaning up HAL";
  awaiting_hal_cleanup_command_ = true;
  awaiting_hal_event_loop_termination_ = true;
  state_ = State::STOPPING;
  hal_func_table_.wifi_cleanup(hal_handle_, NoopHalCleanupHandler);
  awaiting_hal_cleanup_command_ = false;
  LOG(VERBOSE) << "HAL cleanup command complete";
  FinishHalCleanup();
  return Void();
}

void WifiHalService::DoHalEventLoop() {
  LOG(VERBOSE) << "Starting HAL event loop";
  hal_func_table_.wifi_event_loop(hal_handle_);
  if (state_ != State::STOPPING) {
    LOG(FATAL) << "HAL event loop terminated, but HAL was not stopping";
  }
  LOG(VERBOSE) << "HAL Event loop terminated";
  event_loop_thread_.detach();
  PostTask([this](){
      awaiting_hal_event_loop_termination_ = false;
      FinishHalCleanup();
    });
}

void WifiHalService::FinishHalCleanup() {
  if (!awaiting_hal_cleanup_command_ && !awaiting_hal_event_loop_termination_) {
    state_ = State::STOPPED;
    LOG(INFO) << "HAL cleanup complete";
    for (auto& callback : callbacks_) {
      callback->onStop();
    }
  }
}


Return<void> WifiHalService::getChip(getChip_cb cb) {
  cb(sp<V1_0::IWifiChip>()); // TODO return a real IWifiChip implementation
  return Void();
}


void WifiHalService::PostTask(const std::function<void()>& callback) {
  sp<MessageHandler> message_handler =
      new FunctionMessageHandler(callback);
  looper_->sendMessage(message_handler, NULL);
}

}  // namespace wifi
}  // namespace hardware
}  // namespace android
