/**
 * Copyright (C) 2018 The Android Open Source Project
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

#ifndef COMMON_H
#define COMMON_H

#include <time.h>
#define MAX_TEST_DURATION 300

// exit status code
#define EXIT_VULNERABLE 113

time_t start_timer(void);
int timer_active(time_t timer_started);

time_t start_timer(){
  return time(NULL);
}

int timer_active(time_t timer_started){
  return time(NULL) < (timer_started + MAX_TEST_DURATION);
}

#endif /* COMMON_H */
