#include <assert.h>
#include <dirent.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>

#include "warp_dpi_engine.h"
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
    char *plain[] = {"warp_dpi", "-U", NULL};
    char *invalid[] = {"warp_dpi", "-U", "--split", "0++", NULL};
    char *unsafe[] = {"warp_dpi", "-U", "--conn-ip", "127.0.0.1", NULL};
    assert(!warp_dpi_start_args(4, unsafe));
    assert(!warp_dpi_start_args(4, invalid));
    for (size_t i = 0; i < sizeof(strategies) / sizeof(*strategies); i++) {
        void *engine = warp_dpi_start_args(strategies[i].length, strategies[i].argv);
        assert(engine);
        assert(!warp_dpi_start_args(2, plain));
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

    void *engine = warp_dpi_start_args(2, plain);
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
    close(listener);

    no_address = true;
    engine = warp_dpi_start_args(2, plain);
    assert(engine);
    client = warp_dpi_open_stream(engine);
    assert(client >= 0);
    readable(client);
    assert(read(client, &byte, 1) == 0);
    warp_dpi_stop(engine);
    close(client);
    assert(count_entries("/proc/self/fd") == descriptors);
    puts("All 18 presets, private stream relay, cancellation, and descriptor cleanup passed");
    return 0;
}
