#include "warp_dpi_engine.h"
#include "byedpi/proxy.h"

#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <pthread.h>
#include <signal.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <string.h>
#include <sys/eventfd.h>

int byedpi_configure(int argc, char **argv);
void clear_params(void);
int warp_private_stream_pair(int *client, int *engine);

struct engine {
    pthread_t worker;
    int stop;
    int worker_stop;
    int stream;
    atomic_bool stopping;
};

static atomic_flag owner = ATOMIC_FLAG_INIT;

static int allowed_arguments(int count, char **argv)
{
    static const char *const with_value[] = {
        "--split", "--disorder", "--tlsrec", "--oob", "--disoob", "--fake", "--ttl"
    };
    for (int i = 2; i < count; i++) {
        if (!strcmp(argv[i], "--md5sig") || !strcmp(argv[i], "--auto=torst")
                || !strcmp(argv[i], "--auto=ssl_err"))
            continue;
        size_t option = 0;
        while (option < sizeof(with_value) / sizeof(*with_value)
                && strcmp(argv[i], with_value[option]))
            option++;
        if (option == sizeof(with_value) / sizeof(*with_value) || ++i == count
                || argv[i][strspn(argv[i], "0123456789+-s")])
            return 0;
    }
    return 1;
}

// Exposed to the native lifecycle test so it exercises the same parser/ownership.
void *warp_dpi_start_args(int count, char **argv)
{
    if (!allowed_arguments(count, argv) || atomic_flag_test_and_set(&owner))
        return NULL;
    struct engine *engine = calloc(1, sizeof(*engine));
    if (engine && !byedpi_configure(count, argv)) {
        engine->stop = -1;
        atomic_init(&engine->stopping, false);
        return engine;
    }
    free(engine);
    atomic_flag_clear(&owner);
    return NULL;
}

static void *run_stream(void *context)
{
    struct engine *engine = context;
    sigset_t signals;
    sigemptyset(&signals);
    sigaddset(&signals, SIGPIPE);
    pthread_sigmask(SIG_BLOCK, &signals, NULL);

    struct addrinfo hints = {
        .ai_family = AF_UNSPEC, .ai_socktype = SOCK_STREAM, .ai_flags = AI_ADDRCONFIG
    };
    struct addrinfo *addresses = NULL;
    if (!atomic_load(&engine->stopping) && !getaddrinfo("api.cloudflareclient.com", "443", &hints, &addresses)
            && addresses) {
        union sockaddr_u target = {0};
        memcpy(&target, addresses->ai_addr, addresses->ai_addrlen);
        freeaddrinfo(addresses);
        if (!atomic_load(&engine->stopping)) {
            byedpi_private_loop(engine->stream, engine->worker_stop, &target);
            return NULL;
        }
    }
    close(engine->stream);
    close(engine->worker_stop);
    return NULL;
}

static void stop_stream(struct engine *engine)
{
    if (engine->stop < 0)
        return;
    atomic_store(&engine->stopping, true);
    while (eventfd_write(engine->stop, 1) < 0 && errno == EINTR) {}
    pthread_join(engine->worker, NULL);
    close(engine->stop);
    engine->stop = -1;
}

int warp_dpi_open_stream(void *handle)
{
    struct engine *engine = handle;
    stop_stream(engine);
    atomic_store(&engine->stopping, false);
    int client;
    if (warp_private_stream_pair(&client, &engine->stream))
        return -1;
    engine->stop = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (engine->stop >= 0) {
        engine->worker_stop = fcntl(engine->stop, F_DUPFD_CLOEXEC, 0);
        if (engine->worker_stop >= 0) {
            if (!pthread_create(&engine->worker, NULL, run_stream, engine))
                return client;
            close(engine->worker_stop);
        }
        close(engine->stop);
        engine->stop = -1;
    }
    close(engine->stream);
    close(client);
    return -1;
}

void warp_dpi_stop(void *handle)
{
    struct engine *engine = handle;
    stop_stream(engine);
    clear_params();
    free(engine);
    atomic_flag_clear(&owner);
}
