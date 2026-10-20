// Lets unmodified librtlsdr open a USB device that Android's UsbManager has already opened.
//
// Untrusted apps can't scan /dev/bus/usb, so libusb's normal enumeration finds nothing.
// Instead the app hands us the UsbDeviceConnection file descriptor, and these replacements
// (wired in by librtlsdr_android.c) make it look like libusb enumerated exactly one device.
#pragma once

#include <libusb.h>
#include <sys/types.h>

// Set the UsbDeviceConnection fd before calling any rtlsdr_* function. The fd stays owned by
// the Java side; libusb never closes a wrapped fd.
void usb_shim_set_fd(int fd);

int shim_libusb_init(libusb_context **ctx);
ssize_t shim_libusb_get_device_list(libusb_context *ctx, libusb_device ***list);
int shim_libusb_open(libusb_device *dev, libusb_device_handle **dev_handle);
void shim_libusb_close(libusb_device_handle *dev_handle);
void shim_libusb_exit(libusb_context *ctx);
