# SPDX-License-Identifier: GPL-2.0
#
# Out-of-tree kbuild for the stockyard kernel module.
# Run inside a Linux VM with matching kernel-headers installed.

obj-m := stockyard_driver.o

KDIR ?= /lib/modules/$(shell uname -r)/build
PWD  := $(CURDIR)

.PHONY: all modules clean modules_install help

all: modules

modules:
	$(MAKE) -C $(KDIR) M=$(PWD) modules

clean:
	$(MAKE) -C $(KDIR) M=$(PWD) clean
	$(RM) Module.symvers modules.order

modules_install:
	$(MAKE) -C $(KDIR) M=$(PWD) modules_install
	depmod -a

help:
	@echo "Targets:"
	@echo "  make            -- build stockyard_driver.ko"
	@echo "  make clean      -- remove build artifacts"
	@echo "  make modules_install (as root) -- install into /lib/modules/`uname -r`/extra"
	@echo ""
	@echo "After build:"
	@echo "  sudo insmod ./stockyard_driver.ko"
	@echo "  ls -l /dev/stockyard"
	@echo "  cd test && make && ./test_read"
