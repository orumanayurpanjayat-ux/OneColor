/*
 * zygote_launcher: stands in for init when starting the guest's zygote.
 *
 * Drop-in replacement for OneLineage's zygote_exec, which does the same socket
 * setup but execs app_process64 with a hardcoded environment, including
 * LineageOS 23.2's BOOTCLASSPATH and SYSTEMSERVERCLASSPATH. That breaks any
 * other guest: ColorOS's zygote aborts in libhwui because oplus-framework.jar
 * is not on the boot class path. This launcher takes the class paths (and the
 * other runtime variables below) from its own environment, as exported by
 * guest_start.sh from the guest's derive_classpath, and only falls back to
 * the original defaults for variables that are not set.
 *
 * Like init's "socket zygote stream 660 root system" it creates the zygote and
 * usap pool sockets, passes them as fds 3 and 4, records app_process64's
 * startup output in the guest data partition, and closes every other fd
 * before the exec. stdin stays on /dev/null.
 *
 * -Xforcejitzygote (always passed by OneLineage's launcher, and by this one up
 * to run 44) only when ONEOS_GUEST_JIT_ZYGOTE=1, which guest_start.sh exports
 * (1 for ColorOS guests, 0 for the others). With it ART ignores the image's
 * AOT boot image, compiles the boot class path in memory at every zygote start
 * (dex2oat64 --compiler-filter=verify) and runs framework code JIT-compiled
 * only. ONEOS_GUEST_JIT_ZYGOTE itself is not passed on to zygote.
 */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#define AID_SYSTEM 1000
#define ZYGOTE_LOG_PATH "/data/zygote_exec.log"

static void redirect_stdio(void) {
    int null_fd = open("/dev/null", O_RDONLY);
    if (null_fd >= 0) {
        if (dup3(null_fd, STDIN_FILENO, 0) < 0) perror("zygote_launcher: stdin /dev/null");
        if (null_fd > STDERR_FILENO) close(null_fd);
    } else {
        perror("zygote_launcher: open /dev/null");
    }

    int log_fd = open(ZYGOTE_LOG_PATH, O_WRONLY | O_APPEND | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (log_fd < 0) {
        fprintf(stderr, "zygote_launcher: cannot open %s; keeping takeover.log stderr: %s\n",
                ZYGOTE_LOG_PATH, strerror(errno));
        return;
    }

    struct stat st;
    if (fstat(log_fd, &st) < 0) {
        int saved_errno = errno;
        close(log_fd);
        fprintf(stderr, "zygote_launcher: refusing unsafe %s; keeping takeover.log stderr: %s\n",
                ZYGOTE_LOG_PATH, strerror(saved_errno));
        return;
    }
    if (!S_ISREG(st.st_mode)) {
        close(log_fd);
        fprintf(stderr, "zygote_launcher: refusing non-regular %s; keeping takeover.log stderr\n",
                ZYGOTE_LOG_PATH);
        return;
    }
    if (fchmod(log_fd, 0600) < 0) {
        int saved_errno = errno;
        close(log_fd);
        fprintf(stderr, "zygote_launcher: cannot protect %s; keeping takeover.log stderr: %s\n",
                ZYGOTE_LOG_PATH, strerror(saved_errno));
        return;
    }

    if (dup3(log_fd, STDOUT_FILENO, 0) < 0 || dup3(log_fd, STDERR_FILENO, 0) < 0) {
        int saved_errno = errno;
        close(log_fd);
        errno = saved_errno;
        perror("zygote_launcher: redirect startup output");
        return;
    }
    if (log_fd > STDERR_FILENO) close(log_fd);
}

static void make_socket(const char *path, int target_fd) {
    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) { perror("socket"); exit(1); }
    if (fd != target_fd) {
        if (dup3(fd, target_fd, 0) < 0) { perror("dup3"); exit(1); }
        close(fd);
    }
    unlink(path);
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
    if (bind(target_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) { perror(path); exit(1); }
    if (listen(target_fd, 10) < 0) { perror("listen"); exit(1); }
    chmod(path, 0660);
    chown(path, 0, AID_SYSTEM);
}

/* Variables taken from our environment when set, else these defaults. */
static const char *const kInherited[][2] = {
    {"PATH", "/system/bin:/system/xbin"},
    {"ANDROID_ROOT", "/system"},
    {"ANDROID_DATA", "/data"},
    {"ANDROID_STORAGE", "/storage"},
    {"ANDROID_ART_ROOT", "/apex/com.android.art"},
    {"ANDROID_I18N_ROOT", "/apex/com.android.i18n"},
    {"ANDROID_TZDATA_ROOT", "/apex/com.android.tzdata"},
    {"BOOTCLASSPATH", NULL},
    {"DEX2OATBOOTCLASSPATH", NULL},
    {"SYSTEMSERVERCLASSPATH", NULL},
    {"STANDALONE_SYSTEMSERVER_JARS", NULL},
    {"EXTERNAL_STORAGE", "/sdcard"},
    {"ASEC_MOUNTPOINT", "/mnt/asec"},
    {"ANDROID_ASSETS", "/system/app"},
};

int main(void) {
    static char *envp[32];
    int n = 0;

    setpgid(0, 0);
    redirect_stdio();
    make_socket("/dev/socket/zygote", 3);
    make_socket("/dev/socket/usap_pool_primary", 4);

    envp[n++] = "ANDROID_SOCKET_zygote=3";
    envp[n++] = "ANDROID_SOCKET_usap_pool_primary=4";
    for (size_t i = 0; i < sizeof(kInherited) / sizeof(kInherited[0]); i++) {
        const char *name = kInherited[i][0];
        const char *value = getenv(name);
        if (!value || !*value) value = kInherited[i][1];
        if (!value) {
            fprintf(stderr, "zygote_launcher: %s is not set\n", name);
            continue;
        }
        size_t len = strlen(name) + strlen(value) + 2;
        char *entry = malloc(len);
        snprintf(entry, len, "%s=%s", name, value);
        envp[n++] = entry;
    }
    envp[n] = NULL;

    const char *jit = getenv("ONEOS_GUEST_JIT_ZYGOTE");
    int force_jit = jit && strcmp(jit, "1") == 0;
    /* The launch choice is included with app_process64's startup output. */
    fprintf(stderr, "zygote_launcher: %s (ONEOS_GUEST_JIT_ZYGOTE=%s)\n",
            force_jit ? "-Xforcejitzygote" : "the image's boot image, no -Xforcejitzygote",
            jit ? jit : "unset");

    for (int fd = 5; fd < 1024; fd++) close(fd);

    char *argv[8];
    int a = 0;
    argv[a++] = "zygote64";
    argv[a++] = "-Xzygote";
    if (force_jit) argv[a++] = "-Xforcejitzygote";
    argv[a++] = "/system/bin";
    argv[a++] = "--zygote";
    argv[a++] = "--start-system-server";
    argv[a++] = "--socket-name=zygote";
    argv[a] = NULL;
    execve("/system/bin/app_process64", argv, envp);
    perror("zygote_launcher: execve /system/bin/app_process64");
    return 127;
}
