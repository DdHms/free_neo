"""Launch local video decoder with file and descriptor resource limits."""
import os
import resource
import sys


def main():
    if len(sys.argv) < 3 or sys.argv[1] != '--' or not os.path.isabs(sys.argv[2]):
        raise SystemExit('expected an absolute decoder path')
    for key, maximum in ((resource.RLIMIT_NOFILE, 64), (resource.RLIMIT_FSIZE, 4194304)):
        _, hard = resource.getrlimit(key)
        target = maximum if hard == resource.RLIM_INFINITY else min(maximum, hard)
        resource.setrlimit(key, (target, hard))
    os.execv(sys.argv[2], sys.argv[2:])


if __name__ == '__main__':
    main()
