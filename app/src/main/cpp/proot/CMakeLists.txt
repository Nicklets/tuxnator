cmake_minimum_required(VERSION 3.22.1)

project(PRoot)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

add_library(talloc STATIC
            talloc/talloc.c)

include_directories(src
                    talloc)

add_executable(libproot.so
               src/cli/cli.c
               src/cli/proot.c
               src/cli/note.c
               src/execve/enter.c
               src/execve/exit.c
               src/execve/elf.c
               src/execve/auxv.c
               src/path/binding.c
               src/path/glue.c
               src/path/canon.c
               src/path/path.c
               src/path/proc.c
               src/path/temp.c
               src/syscall/seccomp.c
               src/syscall/syscall.c
               src/syscall/chain.c
               src/syscall/enter.c
               src/syscall/exit.c
               src/syscall/sysnum.c
               src/syscall/socket.c
               src/syscall/heap.c
               src/syscall/rlimit.c
               src/tracee/tracee.c
               src/tracee/mem.c
               src/tracee/reg.c
               src/tracee/event.c
               src/tracee/seccomp.c
               src/ptrace/ptrace.c
               src/ptrace/wait.c)

target_link_libraries(libproot.so
                      talloc)

add_library(proot-loader SHARED
            src/loader/loader.c)

# The loader is executed directly by the kernel inside the tracee, without any dynamic linker,
# so it must not reference libc: no builtins (the compiler would turn its zeroing loops into
# memset@plt calls that are never resolved), no stack protector and no libc/crt at link time.
target_compile_options(proot-loader PRIVATE -ffreestanding -fno-builtin -fno-stack-protector)
target_link_options(proot-loader PRIVATE -nostdlib -Wl,-e,_start)