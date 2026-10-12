#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
纯 Python 版 zipalign —— 替掉 build-tools 里那个 x86_64 的 ELF。

为什么必须对齐：Android 11(R) 起，targetSdk >= 30 的 APK 要求
resources.arsc 必须「不压缩」且数据起始地址 4 字节对齐，否则安装直接失败：
  Targeting R+ (version 30 and above) requires the resources.arsc of installed
  APKs to be stored uncompressed and aligned on a 4-byte boundary

做法：把所有条目重写成 STORED（不压缩），用 local header 的 extra 字段补零，
使每条数据的起始偏移都对齐到 align 字节，然后重建 central directory。
本机没有可用的 ARM64 zipalign，所以这里自己实现。
"""
import struct
import sys
import zipfile
import zlib

PAD_ID = 0xD935          # 任意的 extra header id，只用来装对齐填充


def dos_time(dt):
    return (dt[3] << 11) | (dt[4] << 5) | (dt[5] // 2)


def dos_date(dt):
    return ((max(dt[0], 1980) - 1980) << 9) | (dt[1] << 5) | dt[2]


def main():
    if len(sys.argv) < 3:
        print("用法: zipalign.py <in.apk> <out.apk> [align=4]")
        return 2
    src, dst = sys.argv[1], sys.argv[2]
    align = int(sys.argv[3]) if len(sys.argv) > 3 else 4

    zf = zipfile.ZipFile(src)
    out = open(dst, "wb")
    central = []

    for zi in zf.infolist():
        if zi.is_dir():
            continue
        data = zf.read(zi.filename)
        name = zi.filename.encode("utf-8")
        crc = zlib.crc32(data) & 0xFFFFFFFF
        size = len(data)

        offset = out.tell()
        header_len = 30 + len(name)
        pad = (align - ((offset + header_len) % align)) % align
        extra = b""
        if pad:
            # extra 记录本身要 4 字节头，所以小于 6 字节的填充没有意义
            if pad < 6:
                pad += align
            extra = struct.pack("<HH", PAD_ID, pad - 4) + b"\x00" * (pad - 4)

        out.write(struct.pack(
            "<IHHHHHIIIHH",
            0x04034B50,          # local file header signature
            20,                  # version needed
            0,                   # flags
            0,                   # method = STORED
            dos_time(zi.date_time), dos_date(zi.date_time),
            crc, size, size,
            len(name), len(extra)))
        out.write(name)
        out.write(extra)
        out.write(data)

        central.append((zi, name, crc, size, offset))

    cd_offset = out.tell()
    for zi, name, crc, size, offset in central:
        out.write(struct.pack(
            "<IHHHHHHIIIHHHHHII",
            0x02014B50,          # central file header signature
            20,                  # version made by
            20,                  # version needed
            0,                   # flags
            0,                   # method = STORED
            dos_time(zi.date_time), dos_date(zi.date_time),
            crc, size, size,
            len(name), 0, 0, 0, 0,
            zi.external_attr, offset))
        out.write(name)

    cd_size = out.tell() - cd_offset
    out.write(struct.pack(
        "<IHHHHIIH",
        0x06054B50,
        0, 0,                       # 本磁盘号 / 中央目录所在磁盘号
        len(central), len(central),  # 本磁盘条目数 / 总条目数
        cd_size, cd_offset, 0))
    out.close()
    zf.close()

    # 自检：resources.arsc 必须存在且 4 字节对齐
    with open(dst, "rb") as f:
        raw = f.read()
    i = 0
    ok = True
    while True:
        i = raw.find(b"resources.arsc", i)
        if i < 0:
            break
        # 反推 local header：文件名紧跟在 header 后
        hdr = i - 30
        if hdr < 0:
            i += 1
            continue
        sig, ver, flags, method = struct.unpack("<IHHH", raw[hdr:hdr + 10])
        if sig == 0x04034B50 and method == 0:
            namelen, extralen = struct.unpack("<HH", raw[hdr + 26:hdr + 30])
            data_off = hdr + 30 + namelen + extralen
            if data_off % align == 0:
                print("      resources.arsc: STORED, 数据偏移 %d, %% %d == 0 ✔" % (data_off, align))
            else:
                print("      !! resources.arsc 偏移 %d 未对齐" % data_off)
                ok = False
        break
    print("      条目 %d 个, 输出 %.1f KB" % (len(central), len(open(dst, 'rb').read()) / 1024.0))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
