# LK command extraction for Moto Flasher 2.0.8 Extras (verified 2026-11-24)
Sources: factory LK ~/workspace/blankflash-nevada/blankflash/lk.img (3,337,840 B)
         neon v2 LK ~/workspace/user/files/nevada-lk-neon-v2-signed.img (3,337,936 B)
Method: LibLk container parse (512-byte headers, magic 0x58881688); lk partition
file offset 0x200, size 1,234,016; load address 0xffff000050f00000 (both LKs).
Fastboot command table: 16-byte entries, payload 0x100780-0x100850, name ptr + handler ptr.
OEM dispatch table: 24-byte entries, payload 0x11f7f8-0x11fa80, name ptr + handler ptr + flags.
Result: factory and neon v2 command tables are IDENTICAL (same 13 fastboot + same 28 oem
entries, same offsets/handlers); only CERT2/signature region differs.
No `format` command in either LK (format appears 33x only as substrings, never a table entry).
`super` is a PARTITION name (payload 0x100720-0x100770 region), NOT a fastboot command.

## Fastboot commands (13, both LKs, all with real code handlers)
download, flash, getvar, erase, boot, reboot, reboot-bootloader, oem, continue,
flashing, set_active, reboot-recovery, reboot-fastboot

## OEM subcommands (28, dispatch-table verified, both LKs)
config, hw, gpt_test, fb_mode_set, fb_mode_clear, regex, partition, logdump,
blankflash, partitions, show_screen, backlight, logo, ramdump, off-mode-charge,
led, display, batt-voltage, hwid, ab_reset, cid_prov_req, shwi_test, ssm_test,
unlock, lock, get_unlock_data, prod_fuse, read_sv

## String-only OEM names (17, strings present but NOT referenced by any pointer
## in a dispatch table — string-matched in code, unproven as runnable)
cdms, dump_pllk_log, get_socid, mrdump, mrdump_chkimg, mrdump_fallocate,
mrdump_out_set, norts_mrdump, printk-ratelimit, ultraflash, ultraflash_en,
usb2jtag, hwid_list, hwid_list_all, locked, utags_ext, keystore
(keystore has one pointer ref at payload 0x12c960, near payload end)

## ADB diagnosis (2026-11-24)
Adb.kt runs bundled adb via su -c, HOME/ANDROID_USER_HOME at files/adb-home,
LD_LIBRARY_PATH at files/fb/lib. readelf -d toolchain/adb DT_NEEDED:
libabsl_log_internal_message.so, liblog.so, libabsl_vlog_config_internal.so,
libc.so, libprotobuf.so, libz.so.1, libfmt.so, libbrotlidec.so, libbrotlienc.so,
liblz4.so, libzstd.so.1, libc++_shared.so, libdl.so; RUNPATH /data/data/com.termux/files/usr/lib.
Transitive DT_NEEDED closure: NO missing .so — every needed lib is bundled in
assets/lib/ (58 files) or a system bionic lib. Hypothesis (d) ruled out at DT_NEEDED level.
VM cannot reproduce on-device failure (no USB device attached, Android 16 GSI sysroot only).
