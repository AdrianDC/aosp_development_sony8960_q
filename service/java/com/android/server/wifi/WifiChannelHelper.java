/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wifi;

import android.net.wifi.WifiScanner;
import android.util.Slog;

public final class WifiChannelHelper {

    private static final String TAG = "WifiChannelHelper";

    private static final WifiScanner.ChannelSpec NO_CHANNELS[] = new WifiScanner.ChannelSpec[0];
    private static volatile WifiScanner.ChannelSpec sChannels[][];

    private static void copyChannels(
            WifiScanner.ChannelSpec channelSpec[], int offset, int channels[]) {
        for (int i = 0; i < channels.length; i++) {
            channelSpec[offset +i] = new WifiScanner.ChannelSpec(channels[i]);
        }
    }

    private static synchronized void initChannels() {
        if (sChannels != null) {
            return;
        }

        WifiNative wifiNative = WifiNative.getWlanNativeInterface();
        int channels24[] = wifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_24_GHZ);
        if (channels24 == null) {
            return;
        }

        int channels5[] = wifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ);
        if (channels5 == null) {
            return;
        }

        int channelsDfs[] = wifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY);
        if (channelsDfs == null) {
            return;
        }

        sChannels = new WifiScanner.ChannelSpec[8][];

        sChannels[0] = NO_CHANNELS;

        sChannels[1] = new WifiScanner.ChannelSpec[channels24.length];
        copyChannels(sChannels[1], 0, channels24);

        sChannels[2] = new WifiScanner.ChannelSpec[channels5.length];
        copyChannels(sChannels[2], 0, channels5);

        sChannels[3] = new WifiScanner.ChannelSpec[channels24.length + channels5.length];
        copyChannels(sChannels[3], 0, channels24);
        copyChannels(sChannels[3], channels24.length, channels5);

        sChannels[4] = new WifiScanner.ChannelSpec[channelsDfs.length];
        copyChannels(sChannels[4], 0, channelsDfs);

        sChannels[5] = new WifiScanner.ChannelSpec[channels24.length + channelsDfs.length];
        copyChannels(sChannels[5], 0, channels24);
        copyChannels(sChannels[5], channels24.length, channelsDfs);

        sChannels[6] = new WifiScanner.ChannelSpec[channels5.length + channelsDfs.length];
        copyChannels(sChannels[6], 0, channels5);
        copyChannels(sChannels[6], channels5.length, channelsDfs);

        sChannels[7] = new WifiScanner.ChannelSpec[
                channels24.length + channels5.length + channelsDfs.length];
        copyChannels(sChannels[7], 0, channels24);
        copyChannels(sChannels[7], channels24.length, channels5);
        copyChannels(sChannels[7], channels24.length + channels5.length, channelsDfs);
    }

    public static void clearChannelCache() {
        sChannels = null;
    }

    public static WifiScanner.ChannelSpec[] getChannelsForBand(int band) {
        if (sChannels == null) {
            initChannels();
        }

        if (sChannels == null) {
            Slog.e(TAG, "Wifi HAL failed on channel initialization");
            return NO_CHANNELS;
        }

        if (band < WifiScanner.WIFI_BAND_24_GHZ || band > WifiScanner.WIFI_BAND_BOTH_WITH_DFS)
            // invalid value for band
            return NO_CHANNELS;
        else
            return sChannels[band];
    }

    public static WifiScanner.ChannelSpec[] getChannelsForScanSettings(
            WifiScanner.ScanSettings settings) {
        if (settings.band == WifiScanner.WIFI_BAND_UNSPECIFIED) {
            if(settings.channels != null) {
                return settings.channels;
            }
            else {
                return NO_CHANNELS;
            }
        } else {
            return getChannelsForBand(settings.band);
        }
    }

    public static boolean isDfsChannel(int channel) {
        if (sChannels == null) {
            initChannels();
        }
        if (sChannels == null) {
            Slog.e(TAG, "Wifi HAL failed on channel initialization");
            return false;
        }
        for (WifiScanner.ChannelSpec dfsChannel : sChannels[WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY]) {
            if (channel == dfsChannel.frequency) {
                return true;
            }
        }
        return false;
    }

    public static int getBandFromChannel(int frequency) {
        if (2400 <= frequency && frequency < 2500) {
            return WifiScanner.WIFI_BAND_24_GHZ;
        } else if (isDfsChannel(frequency)) {
            return WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY;
        } else if (5100 <= frequency && frequency < 6000) {
            return WifiScanner.WIFI_BAND_5_GHZ;
        }
        return WifiScanner.WIFI_BAND_UNSPECIFIED;
    }

    public static int getBandFromChannels(WifiNative.ChannelSettings[] channels, int numChannels) {
        int band = WifiScanner.WIFI_BAND_UNSPECIFIED;
        for (int c = 0; c < numChannels; c++) {
            band |= getBandFromChannel(channels[c].frequency);
        }
        return band;
    }

    public static int getBandFromChannels(WifiScanner.ChannelSpec[] channels) {
        int band = WifiScanner.WIFI_BAND_UNSPECIFIED;
        for (WifiScanner.ChannelSpec channel : channels) {
            band |= getBandFromChannel(channel.frequency);
        }
        return band;
    }

    private WifiChannelHelper() {
        // don't allow initialization
    }
}
