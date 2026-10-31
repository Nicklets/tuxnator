/*
 * Preloaded into Linux guests: terminal attributes through the ioctls Android allows.
 *
 * Since 2.42, glibc reads and sets terminal attributes with TCGETS2/TCSETS2, and Android's SELinux
 * policy refuses those on a pty with EACCES (only the old TCGETS/TCSETS are allowed for apps). So
 * every program thought it had no terminal: bash in LXTerminal ran without a prompt or line
 * editing, and `tty` said "not a tty". When glibc's call fails with EACCES, these do the same with
 * the old ioctls. isatty and ttyname are here too because glibc calls its tcgetattr internally,
 * where a preload can't reach.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

#define EXPORT __attribute__((visibility("default")))

/* The kernel's struct termios, which TCGETS/TCSETS use (glibc's has more c_cc and the speeds) */
#define KERNEL_NCCS 19
struct kernel_termios {
    tcflag_t c_iflag, c_oflag, c_cflag, c_lflag;
    cc_t c_line;
    cc_t c_cc[KERNEL_NCCS];
};

static int (*real_tcgetattr)(int, struct termios *);
static int (*real_tcsetattr)(int, int, const struct termios *);

static int old_tcgetattr(int fd, struct termios *t) {
    struct kernel_termios k;
    if (ioctl(fd, TCGETS, &k) != 0) return -1;
    memset(t, 0, sizeof(*t));
    t->c_iflag = k.c_iflag;
    t->c_oflag = k.c_oflag;
    t->c_cflag = k.c_cflag;
    t->c_lflag = k.c_lflag;
    t->c_line = k.c_line;
    memset(t->c_cc, _POSIX_VDISABLE, sizeof(t->c_cc));
    memcpy(t->c_cc, k.c_cc, KERNEL_NCCS);
    // A pty has no real speed; this keeps glibc's speed fields consistent with its own encoding
    cfsetispeed(t, B38400);
    cfsetospeed(t, B38400);
    t->c_cflag = (t->c_cflag & ~(CBAUD | CIBAUD)) | (k.c_cflag & (CBAUD | CIBAUD));
    return 0;
}

EXPORT int tcgetattr(int fd, struct termios *t) {
    if (!real_tcgetattr) real_tcgetattr = dlsym(RTLD_NEXT, "tcgetattr");
    if (real_tcgetattr(fd, t) == 0) return 0;
    if (errno != EACCES) return -1;
    return old_tcgetattr(fd, t);
}

EXPORT int tcsetattr(int fd, int action, const struct termios *t) {
    if (!real_tcsetattr) real_tcsetattr = dlsym(RTLD_NEXT, "tcsetattr");
    if (real_tcsetattr(fd, action, t) == 0) return 0;
    if (errno != EACCES) return -1;

    unsigned long request;
    switch (action) {
    case TCSANOW: request = TCSETS; break;
    case TCSADRAIN: request = TCSETSW; break;
    case TCSAFLUSH: request = TCSETSF; break;
    default: errno = EINVAL; return -1;
    }
    struct kernel_termios k;
    if (ioctl(fd, TCGETS, &k) != 0) return -1;
    // Keep the terminal's own baud bits: glibc may have encoded the speed outside c_cflag
    k.c_cflag = (t->c_cflag & ~(CBAUD | CIBAUD)) | (k.c_cflag & (CBAUD | CIBAUD));
    k.c_iflag = t->c_iflag;
    k.c_oflag = t->c_oflag;
    k.c_lflag = t->c_lflag;
    k.c_line = t->c_line;
    memcpy(k.c_cc, t->c_cc, KERNEL_NCCS);
    return ioctl(fd, request, &k);
}

EXPORT int isatty(int fd) {
    struct termios t;
    if (tcgetattr(fd, &t) == 0) return 1;
    if (errno != EBADF) errno = ENOTTY;
    return 0;
}

EXPORT int ttyname_r(int fd, char *buf, size_t len) {
    if (!isatty(fd)) return errno;
    if (len < 2) return ERANGE;
    char link[32];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
    ssize_t n = readlink(link, buf, len - 1);
    if (n < 0) return errno;
    if ((size_t)n >= len - 1) return ERANGE;
    buf[n] = '\0';
    return 0;
}

EXPORT char *ttyname(int fd) {
    static char name[PATH_MAX];
    int error = ttyname_r(fd, name, sizeof(name));
    if (error) {
        errno = error;
        return NULL;
    }
    return name;
}
