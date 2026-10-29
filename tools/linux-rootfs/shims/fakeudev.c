/*
 * Preloaded into Linux guests in place of libudev's monitor API.
 *
 * Android's SELinux policy doesn't let apps create NETLINK_KOBJECT_UEVENT sockets, so
 * udev_monitor_new_from_netlink() always fails, and it is slow to fail (systemd parses
 * /proc/self/mountinfo and sets up logging first, all through PRoot). SDL2 needs a monitor to
 * finish SDL_hid_init(), which SDL_JoystickUpdate() retries on every frame while it fails: that
 * was about a third of SuperTuxKart's main thread.
 *
 * The monitors handed out here never report an event: hotplug is invisible to udev users either
 * way, but now they finish initializing. Everything else (enumeration, devices) is the real
 * libudev, which this library links so SDL's "is libudev already loaded" lookup finds the whole
 * API in the global scope, these functions included, and never dlopens its own copy.
 */
#include <errno.h>
#include <stdlib.h>
#include <sys/eventfd.h>
#include <unistd.h>

#define EXPORT __attribute__((visibility("default")))

struct udev;
struct udev_device;
struct udev *udev_ref(struct udev *udev);
struct udev *udev_unref(struct udev *udev);

struct udev_monitor {
    int refcount;
    int fd;
    struct udev *udev;
};

EXPORT struct udev_monitor *udev_monitor_new_from_netlink(struct udev *udev, const char *name) {
    (void)name;
    if (!udev) {
        errno = EINVAL;
        return NULL;
    }
    struct udev_monitor *monitor = calloc(1, sizeof(*monitor));
    if (!monitor) return NULL;
    // Something to poll that never becomes readable
    monitor->fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (monitor->fd < 0) {
        free(monitor);
        return NULL;
    }
    monitor->refcount = 1;
    monitor->udev = udev_ref(udev);
    return monitor;
}

EXPORT struct udev_monitor *udev_monitor_ref(struct udev_monitor *monitor) {
    if (monitor) monitor->refcount++;
    return monitor;
}

EXPORT struct udev_monitor *udev_monitor_unref(struct udev_monitor *monitor) {
    if (monitor && --monitor->refcount == 0) {
        close(monitor->fd);
        udev_unref(monitor->udev);
        free(monitor);
    }
    return NULL;
}

EXPORT struct udev *udev_monitor_get_udev(struct udev_monitor *monitor) {
    return monitor ? monitor->udev : NULL;
}

EXPORT int udev_monitor_get_fd(struct udev_monitor *monitor) {
    return monitor ? monitor->fd : -EINVAL;
}

EXPORT struct udev_device *udev_monitor_receive_device(struct udev_monitor *monitor) {
    (void)monitor;
    errno = EAGAIN;
    return NULL;
}

EXPORT int udev_monitor_enable_receiving(struct udev_monitor *monitor) {
    return monitor ? 0 : -EINVAL;
}

EXPORT int udev_monitor_set_receive_buffer_size(struct udev_monitor *monitor, int size) {
    (void)size;
    return monitor ? 0 : -EINVAL;
}

EXPORT int udev_monitor_filter_add_match_subsystem_devtype(struct udev_monitor *monitor, const char *subsystem,
                                                           const char *devtype) {
    (void)subsystem;
    (void)devtype;
    return monitor ? 0 : -EINVAL;
}

EXPORT int udev_monitor_filter_add_match_tag(struct udev_monitor *monitor, const char *tag) {
    (void)tag;
    return monitor ? 0 : -EINVAL;
}

EXPORT int udev_monitor_filter_update(struct udev_monitor *monitor) {
    return monitor ? 0 : -EINVAL;
}

EXPORT int udev_monitor_filter_remove(struct udev_monitor *monitor) {
    return monitor ? 0 : -EINVAL;
}
