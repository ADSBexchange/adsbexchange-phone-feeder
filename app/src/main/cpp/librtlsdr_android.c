// Builds upstream librtlsdr.c with its device enumeration redirected to usb_shim.c.
//
// libusb.h is included first so its prototypes keep their real names; the macros then only
// rename the calls inside librtlsdr.c (its own #include <libusb.h> is a no-op by then).
// Doing this with macros instead of a patch file keeps librtlsdr's source untouched.

#include <libusb.h>

#include "usb_shim.h"

#define libusb_init             shim_libusb_init
#define libusb_get_device_list  shim_libusb_get_device_list
#define libusb_open             shim_libusb_open
#define libusb_close            shim_libusb_close
#define libusb_exit             shim_libusb_exit

#include "librtlsdr.c"
