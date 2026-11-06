// SPDX-License-Identifier: MIT
/*
 * rs485ctl: show or set a serial port's kernel RS-485 mode (TIOCGRS485 /
 * TIOCSRS485), so tools that know nothing about RS-485 can still use the
 * PLC's RS485 port with the kernel driving DE.
 *   rs485ctl /dev/ttyS2            show
 *   rs485ctl /dev/ttyS2 on|off     enable (DE high while sending) / disable
 */
#include <fcntl.h>
#include <linux/serial.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

int main(int argc, char **argv)
{
	struct serial_rs485 rs;
	int fd;

	if (argc < 2 || argc > 3) {
		fprintf(stderr, "usage: %s <tty> [on|off]\n", argv[0]);
		return 2;
	}
	fd = open(argv[1], O_RDWR | O_NOCTTY | O_NONBLOCK);
	if (fd < 0) {
		perror(argv[1]);
		return 1;
	}
	memset(&rs, 0, sizeof(rs));
	if (ioctl(fd, TIOCGRS485, &rs) < 0) {
		perror("TIOCGRS485");
		return 1;
	}
	if (argc == 3) {
		if (!strcmp(argv[2], "on")) {
			rs.flags |= SER_RS485_ENABLED | SER_RS485_RTS_ON_SEND;
			rs.flags &= ~SER_RS485_RTS_AFTER_SEND;
		} else if (!strcmp(argv[2], "off")) {
			rs.flags &= ~SER_RS485_ENABLED;
		} else {
			fprintf(stderr, "mode must be on or off\n");
			return 2;
		}
		if (ioctl(fd, TIOCSRS485, &rs) < 0) {
			perror("TIOCSRS485");
			return 1;
		}
		ioctl(fd, TIOCGRS485, &rs);
	}
	printf("%s: rs485 %s, rts_on_send=%d rts_after_send=%d rx_during_tx=%d delay_before=%u delay_after=%u\n",
	       argv[1], (rs.flags & SER_RS485_ENABLED) ? "on" : "off",
	       !!(rs.flags & SER_RS485_RTS_ON_SEND), !!(rs.flags & SER_RS485_RTS_AFTER_SEND),
	       !!(rs.flags & SER_RS485_RX_DURING_TX), rs.delay_rts_before_send, rs.delay_rts_after_send);
	close(fd);
	return 0;
}
