#!/usr/bin/env python3
"""刷新 native_shell 的版本号，保证每次构建都能覆盖安装。

为什么要这个：versionCode 不变的话，点 APK 安装 / adb install -r 会被系统当成
同一个版本，某些 ROM 直接拒绝，表现就是「改了代码但装不上，或者装了还是旧的」。
这里用「距 1970 的分钟数」当 versionCode —— 单调递增、远小于 int32 上限；
versionName 带秒级时间戳，方便在「我的」页面认出是哪一次构建。

用法：
    python tool/stamp_version.py          # 写入 native_shell/version.properties
    python tool/stamp_version.py --print  # 只打印，不改文件

注：这里是精简版，只刷 native_shell/ 的版本号，够本项目用。
"""

import argparse
import datetime
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SHELL = os.path.join(os.path.dirname(HERE), 'native_shell')

# 发版时只改这一行。构建号（时间戳/分钟数）由下面自动生成。
APP_VERSION = '1.1.2'


def build_stamp(now=None):
    now = now or datetime.datetime.now()
    code = int(now.timestamp() // 60)
    name = APP_VERSION + '-' + now.strftime('%Y%m%d%H%M%S')
    return name, code, now.strftime('%Y-%m-%d %H:%M:%S')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--print', dest='just_print', action='store_true',
                    help='只打印版本号，不改文件')
    a = ap.parse_args()

    name, code, human = build_stamp()
    if a.just_print:
        print(name, code)
        return

    path = os.path.join(SHELL, 'version.properties')
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write('# 由 tool/stamp_version.py 生成，请勿手改。\n'
                'versionName=%s\n'
                'versionCode=%d\n'
                'buildStamp=%s\n' % (name, code, human))
    print('versionName=%s  versionCode=%d  (%s)' % (name, code, human))
    print('已写入', path)


if __name__ == '__main__':
    main()
