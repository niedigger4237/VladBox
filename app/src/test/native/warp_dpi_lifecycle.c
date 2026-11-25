#include <assert.h>
#include <dirent.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>

#include "warp_dpi_engine.h"
#include "warp_dpi_arguments.h"
#include "byedpi/params.h"
#include "warp_dpi_test_strategies.h"

static char test_port[6];
static bool no_address;

int __real_getaddrinfo(const char *, const char *, const struct addrinfo *, struct addrinfo **);

int __wrap_getaddrinfo(const char *host, const char *port,
        const struct addrinfo *hints, struct addrinfo **out)
{
    assert(!strcmp(host, "api.cloudflareclient.com"));
    assert(!strcmp(port, "443"));
    if (no_address)
        return EAI_NONAME;
    return __real_getaddrinfo("127.0.0.1", test_port, hints, out);
}

static int count_entries(const char *path)
{
    DIR *dir = opendir(path);
    assert(dir);
    int count = 0;
    while (readdir(dir))
        count++;
    closedir(dir);
    return count;
}

static void readable(int fd)
{
    struct pollfd event = {.fd = fd, .events = POLLIN};
    assert(poll(&event, 1, 3000) == 1);
}

static void *stop_from_other_thread(void *engine)
{
    warp_dpi_stop(engine);
    return NULL;
}

int main(void)
{
    const int descriptors = count_entries("/proc/self/fd");
    char error[WARP_DPI_ERROR_SIZE];
    const struct {
        char *argv[4];
        int count;
        const char *message;
    } invalid[] = {
        {{"--split", "0++"}, 2, "Invalid value"},
        {{"-d1:0:2+sm"}, 1, "Invalid value"},
        {{"-r1:999999999"}, 1, "Invalid value"},
        {{"--fake-offset=-999999999"}, 1, "Invalid value"},
        {{"-At,r,s,c"}, 1, "Invalid value"},
        {{"-Qr,m=Inf"}, 1, "Invalid value"},
        {{"--round=3-1"}, 1, "Invalid value"},
        {{"--pf=443-80"}, 1, "Invalid value"},
        {{"-n", "https://other.example/"}, 2, "requires a DNS name"},
        {{"-n", "{other}"}, 2, "requires a DNS name"},
        {{"--split"}, 1, "Missing value"},
        {{"--split", "--connect-to=127.0.0.1"}, 2, "Missing value"},
        {{"--connect-to=127.0.0.1"}, 1, "Unsupported or restricted"},
        {{"-C127.0.0.1"}, 1, "Unsupported or restricted"},
        {{"-I127.0.0.1"}, 1, "Unsupported or restricted"},
        {{"--port=1080"}, 1, "Unsupported or restricted"},
        {{"-SZG"}, 1, "Unsupported or restricted"},
        {{"--protect-path=/tmp/protect"}, 1, "Unsupported or restricted"},
        {{"--fake-data=/proc/self/maps"}, 1, "Unsupported or restricted"},
        {{"--hosts=/proc/self/maps"}, 1, "Unsupported or restricted"},
        {{"--spl=1"}, 1, "Unsupported or restricted"},
        {{"--", "-s1"}, 2, "Unsupported or restricted"},
    };
    for (size_t i = 0; i < sizeof(invalid) / sizeof(*invalid); i++) {
        char *args[4];
        memcpy(args, invalid[i].argv, sizeof(args));
        assert(warp_dpi_validate_args(invalid[i].count, args, error));
        assert(strstr(error, invalid[i].message));
        assert(!warp_dpi_start_args(invalid[i].count, args));
        assert(count_entries("/proc/self/fd") == descriptors);
        assert(!warp_dpi_validate_args(0, NULL, error));
    }
    char *complex[] = {
        "-SZ", "-s1:5:2+sm", "--disorder=2:3:4+he", "-q", "3+nr",
        "--fake-offset", "4", "-f-203", "-Q", "r,o,m=-100", "--fake-sni={sni}",
        "-n", "example.org", "--auto=torst,ssl_err", "-r-5+se",
        "--round=1-2", "-V443", "-e\\x2a", "-Mh,d,r", "-t", "6",
        "--tlsminor=2", "--await-int=10"
    };
    int complex_count = sizeof(complex) / sizeof(*complex);
    assert(complex_count > 16);
    assert(!warp_dpi_validate_args(complex_count, complex, error));
    struct warp_arguments normalized;
    assert(!warp_arguments_prepare(complex_count, complex, &normalized, error));
    bool has_warp_sni = false;
    for (int i = 2; i < normalized.count; i++)
        has_warp_sni |= !strcmp(normalized.argv[i], "--fake-sni=" WARP_DPI_HOST);
    assert(has_warp_sni);
    warp_arguments_free(&normalized);

    char *large[WARP_DPI_MAX_ARGS + 1];
    for (size_t i = 0; i < sizeof(large) / sizeof(*large); i++)
        large[i] = "-s1";
    assert(!warp_dpi_validate_args(WARP_DPI_MAX_ARGS, large, error));
    assert(warp_dpi_validate_args(WARP_DPI_MAX_ARGS + 1, large, error));
    for (int i = 0; i < 64; i++)
        large[i] = "-At";
    assert(!warp_dpi_validate_args(62, large, error));
    assert(warp_dpi_validate_args(64, large, error));

    for (size_t i = 0; i < sizeof(strategies) / sizeof(*strategies); i++) {
        assert(!warp_dpi_validate_args(strategies[i].length, strategies[i].argv, error));
        void *engine = warp_dpi_start_args(strategies[i].length, strategies[i].argv);
        assert(engine);
        assert(!warp_dpi_start_args(0, NULL));
        assert(warp_dpi_validate_args(0, NULL, error));
        assert(strstr(error, "already running"));
        pthread_t stopper;
        assert(!pthread_create(&stopper, NULL, stop_from_other_thread, engine));
        assert(!pthread_join(stopper, NULL));
        assert(count_entries("/proc/self/fd") == descriptors);
    }

    int listener = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    assert(listener >= 0);
    struct sockaddr_in addr = {
        .sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK)
    };
    assert(!bind(listener, (struct sockaddr *)&addr, sizeof(addr)));
    assert(!listen(listener, 1));
    socklen_t len = sizeof(addr);
    assert(!getsockname(listener, (struct sockaddr *)&addr, &len));
    snprintf(test_port, sizeof(test_port), "%u", ntohs(addr.sin_port));

    void *engine = warp_dpi_start_args(0, NULL);
    assert(engine);
    for (int i = 0; i < 20; i++) {
        int client = warp_dpi_open_stream(engine);
        assert(client >= 0 && fcntl(client, F_GETFD) & FD_CLOEXEC);
        struct sockaddr_storage peer;
        len = sizeof(peer);
        assert(!getpeername(client, (struct sockaddr *)&peer, &len));
        assert(peer.ss_family == AF_UNIX);
        readable(listener);
        int server = accept(listener, NULL, NULL);
        assert(server >= 0);
        char byte = 0;
        assert(write(client, "q", 1) == 1);
        readable(server);
        assert(read(server, &byte, 1) == 1 && byte == 'q');
        assert(write(server, "a", 1) == 1);
        readable(client);
        assert(read(client, &byte, 1) == 1 && byte == 'a');
        close(server);
        close(client);
    }
    int client = warp_dpi_open_stream(engine);
    assert(client >= 0);
    warp_dpi_stop(engine);
    readable(client);
    char byte;
    assert(read(client, &byte, 1) == 0);
    close(client);
    char *temporary_sni = strdup(WARP_DPI_HOST);
    assert(temporary_sni);
    char *fake_args[] = {"-n", temporary_sni, "-f12", "-O4", "-Qr", "-t8"};
    engine = warp_dpi_start_args(sizeof(fake_args) / sizeof(*fake_args), fake_args);
    free(temporary_sni);
    assert(engine);
    client = warp_dpi_open_stream(engine);
    assert(client >= 0);
    readable(listener);
    int server = accept(listener, NULL, NULL);
    assert(server >= 0);
    assert(write(client, fake_tls.data, fake_tls.size) == fake_tls.size);
    readable(server);
    char fake_bytes[32];
    assert(read(server, fake_bytes, sizeof(fake_bytes)) > 0);
    warp_dpi_stop(engine);
    close(server);
    close(client);
    close(listener);

    no_address = true;
    engine = warp_dpi_start_args(0, NULL);
    assert(engine);
    client = warp_dpi_open_stream(engine);
    assert(client >= 0);
    readable(client);
    assert(read(client, &byte, 1) == 0);
    warp_dpi_stop(engine);
    close(client);
    assert(count_entries("/proc/self/fd") == descriptors);
    puts("55 presets, custom syntax, rejection paths, private relay, cancellation, and descriptor cleanup passed");
    return 0;
}
