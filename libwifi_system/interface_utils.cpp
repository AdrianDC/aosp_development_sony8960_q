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

#include "wifi_system/interface_utils.h"

#include <netinet/in.h>
#include <sys/socket.h>
/* We need linux/if.h for flags like IFF_UP.  Sadly, it forward declares
   struct sockaddr and must be included after sys/socket.h. */
#include <linux/if.h>

#include <android-base/unique_fd.h>
#include <log/log.h>

namespace android {
namespace wifi_system {
namespace {

const char kWlan0InterfaceName[] = "wlan0";

}  // namespace

bool set_iface_up(const char* if_name, bool request_up) {
  base::unique_fd sock(socket(PF_INET, SOCK_DGRAM, 0));
  if (sock.get() < 0) {
    ALOGE("Bad socket: %d, errno: %d\n", sock.get(), errno);
    return false;
  }

  struct ifreq ifr;
  memset(&ifr, 0, sizeof(ifr));
  if (strlcpy(ifr.ifr_name, if_name, sizeof(ifr.ifr_name)) >=
      sizeof(ifr.ifr_name)) {
    ALOGE("Interface name is too long: %s\n", if_name);
    return false;
  }

  if (TEMP_FAILURE_RETRY(ioctl(sock.get(), SIOCGIFFLAGS, &ifr)) != 0) {
    ALOGE("Could not read interface %s errno: %d\n", if_name, errno);
    return false;
  }

  const bool currently_up = ifr.ifr_flags & IFF_UP;
  if (currently_up == request_up) {
    return true;
  }

  if (request_up) {
    ifr.ifr_flags |= IFF_UP;
  } else {
    ifr.ifr_flags &= ~IFF_UP;
  }

  if (TEMP_FAILURE_RETRY(ioctl(sock.get(), SIOCSIFFLAGS, &ifr)) != 0) {
    ALOGE("Could not set interface %s flags: %d\n", if_name, errno);
    return false;
  }

  return true;
}

bool set_wifi_iface_up(bool request_up) {
  return set_iface_up(kWlan0InterfaceName, request_up);
}

}  // namespace wifi_system
}  // namespace android
