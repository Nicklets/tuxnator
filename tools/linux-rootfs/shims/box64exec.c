/*
 * Preloaded into Linux guests: runs x86 programs through Box64 when something executes them.
 *
 * A Linux desktop would rely on binfmt_misc to start an x86_64 binary that a file manager, a
 * launcher or a script executes, but PRoot can't offer it. So execve() and the functions that
 * end in it without going through the exported symbol (execvp*, posix_spawn*) look at the ELF
 * header first and run "box64 <program> <args>" for x86 executables. Everything else is passed
 * through untouched. Box64 runs fine with this preloaded: the programs it starts are already
 * prefixed with box64 when they reach here.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define EXPORT __attribute__((visibility("default")))
#define BOX64 "/usr/local/bin/box64"

extern char **environ;

static int is_x86_elf(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    Elf64_Ehdr header;
    ssize_t n = read(fd, &header, sizeof(header));
    close(fd);
    if (n < (ssize_t)(EI_NIDENT + 4) || memcmp(header.e_ident, ELFMAG, SELFMAG) != 0) return 0;
    // e_machine sits at the same offset in 32-bit headers
    return header.e_machine == EM_X86_64 || header.e_machine == EM_386;
}

/* Resolves a name like execvp() does; returns 0 and fills out with the path, or -1 */
static int resolve(const char *file, char *out, size_t size) {
    if (strchr(file, '/')) {
        if (strlen(file) >= size) return -1;
        strcpy(out, file);
        return 0;
    }
    const char *path = getenv("PATH");
    if (!path) path = "/usr/local/bin:/usr/bin:/bin";
    while (*path) {
        const char *end = strchr(path, ':');
        if (!end) end = path + strlen(path);
        size_t len = end - path;
        if (len + 1 + strlen(file) < size) {
            memcpy(out, path, len);
            out[len] = '/';
            strcpy(out + len + 1, file);
            if (access(out, X_OK) == 0) return 0;
        }
        path = *end ? end + 1 : end;
    }
    return -1;
}

/* argv for "box64 path args...", or NULL when path isn't an x86 program (caller frees it) */
static char **box64_argv(const char *path, char *const argv[]) {
    if (!is_x86_elf(path)) return NULL;
    size_t argc = 0;
    while (argv && argv[argc]) argc++;
    char **args = malloc((argc + 2) * sizeof(char *));
    if (!args) return NULL;
    args[0] = (char *)BOX64;
    args[1] = (char *)path;
    for (size_t i = 1; i < argc; i++) args[i + 1] = argv[i];
    args[argc + 1] = NULL;
    return args;
}

typedef int (*execve_fn)(const char *, char *const[], char *const[]);
typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                        const posix_spawnattr_t *, char *const[], char *const[]);

EXPORT int execve(const char *path, char *const argv[], char *const envp[]) {
    static execve_fn real;
    if (!real) real = (execve_fn)dlsym(RTLD_NEXT, "execve");
    char **args = box64_argv(path, argv);
    if (!args) return real(path, argv, envp);
    int result = real(BOX64, args, envp);
    int saved = errno;
    free(args);
    errno = saved;
    return result;
}

EXPORT int execv(const char *path, char *const argv[]) {
    return execve(path, argv, environ);
}

/* argv for "/bin/sh path args..." (caller frees it) */
static char **shell_argv(const char *path, char *const argv[]) {
    size_t argc = 0;
    while (argv && argv[argc]) argc++;
    char **args = malloc((argc + 2) * sizeof(char *));
    if (!args) return NULL;
    args[0] = (char *)"/bin/sh";
    args[1] = (char *)path;
    for (size_t i = 1; i < argc; i++) args[i + 1] = argv[i];
    args[argc + 1] = NULL;
    return args;
}

EXPORT int execvpe(const char *file, char *const argv[], char *const envp[]) {
    char path[PATH_MAX];
    if (resolve(file, path, sizeof(path)) != 0) {
        errno = ENOENT;
        return -1;
    }
    execve(path, argv, envp);
    // Like glibc's execvp: ENOEXEC means a script to run with the shell (PRoot returns it even
    // for scripts with a #! line, so callers that relied on glibc's fallback would fail here)
    if (errno == ENOEXEC) {
        char **args = shell_argv(path, argv);
        if (args) {
            execve("/bin/sh", args, envp);
            free(args);
        }
    }
    return -1;
}

EXPORT int execvp(const char *file, char *const argv[]) {
    return execvpe(file, argv, environ);
}

static int spawn(spawn_fn real, pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                 const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    char **args = box64_argv(path, argv);
    if (!args) return real(pid, path, actions, attr, argv, envp);
    int result = real(pid, BOX64, actions, attr, args, envp);
    free(args);
    return result;
}

EXPORT int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                       const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    static spawn_fn real;
    if (!real) real = (spawn_fn)dlsym(RTLD_NEXT, "posix_spawn");
    return spawn(real, pid, path, actions, attr, argv, envp);
}

EXPORT int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *actions,
                        const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    static spawn_fn real;
    if (!real) real = (spawn_fn)dlsym(RTLD_NEXT, "posix_spawn");
    char path[PATH_MAX];
    if (resolve(file, path, sizeof(path)) != 0) return ENOENT;
    int result = spawn(real, pid, path, actions, attr, argv, envp);
    if (result == ENOEXEC) {
        // The same shell fallback glibc's posix_spawnp has
        char **args = shell_argv(path, argv);
        if (args) {
            result = real(pid, "/bin/sh", actions, attr, args, envp);
            free(args);
        }
    }
    return result;
}
