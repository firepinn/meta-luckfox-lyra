// SPDX-License-Identifier: GPL-2.0+
/*
 * Driver for the WCH CH182H 10/100 Ethernet PHY.
 *
 * The CH182H does not start operating after a plain hardware reset. It comes
 * up correctly configured but inert, and stays that way until it has seen an
 * analog power-down -> power-up transition.
 *
 * Measured on a Luckfox Lyra Pi (RK3506B, dual RMII, one CH182H per port).
 * Both ports behaved identically. Immediately after boot:
 *
 *	BMCR   0x3100	autoneg enabled, 100/Full, not powered down, not isolated
 *	BMSR   0x7849	link = 0, autoneg-complete = 0
 *	ANLPAR 0x0001	link partner abilities empty - nothing being received
 *
 * After writing BMCR_PDOWN and clearing it again, with no other action, the
 * same PHY links immediately:
 *
 *	BMSR   0x786d	link = 1, autoneg COMPLETE
 *	ANLPAR 0xc1e1	link partner abilities present
 *
 * The reason this is not papered over by the normal boot path is that
 * phy_start() -> genphy_resume() only *clears* BMCR_PDOWN. At boot that bit
 * was never set, so the resume is a no-op and the PHY is never cycled. An
 * `ip link set <dev> down/up` does fix it, because phy_stop() suspends the PHY
 * first - which is precisely the missing transition.
 *
 * Things that do NOT recover it, for the record:
 *  - restarting autoneg (BMCR_ANRESTART / `ethtool -r`)
 *  - a longer PHY hardware reset (widening snps,reset-delays-us)
 *  - PHY_RST_AFTER_CLK_EN, which performs a hardware reset via reset-gpios
 *    rather than a power cycle
 *
 * Doing the cycle in .config_init is the correct hook: phylib calls it from
 * phy_init_hw() during phy_attach(), i.e. once the MAC has enabled the PHY's
 * reference clock and before the link is brought up.
 *
 * Hot-plug has a related failure, measured on the Lyra PLC (CH182H2): a port
 * that has not linked once since this init, when a cable arrives, sees the
 * signal (power-save status drops) but never links. Vendor register page 99,
 * reg 0x19 then reads 0x0036, against 0x0022 idle and 0x003d linked. A port
 * that has linked once relinks within a second on every replug. Things that
 * do NOT recover it: WCH's own driver V1.2 recoveries (PCSR force-100 pulse,
 * BMCR soft reset), an autoneg restart, and clearing the power-save enable
 * (page 0 reg 0x18 bit 15, tested both ways from boot). Only the BMCR_PDOWN
 * cycle does, within ~3 s. So .read_status (phylib polls once a second; no
 * IRQ) applies that cycle when it sees the stuck state for
 * CH182H_STUCK_POLLS polls in a row. Idle ports without a cable are never
 * touched. WCH's register manual (CH182DS2) is not public, so what state 0x36
 * means internally is unknown.
 */

#include <linux/kernel.h>
#include <linux/module.h>
#include <linux/mii.h>
#include <linux/phy.h>
#include <linux/delay.h>

#define PHY_ID_CH182H		0x73719126

/* Long enough for the analog block to actually collapse. */
#define CH182H_PDOWN_US		2000
/* Datasheet-free; measured to be comfortably enough for the PLL to relock. */
#define CH182H_PUP_MS		20
/* Consecutive 1 s polls in the stuck state before cycling the PHY. */
#define CH182H_STUCK_POLLS	2

/* Paged vendor registers; page select as in WCH's ch182.c V1.2. */
#define CH182_PAGE_SEL		0x1f
#define CH182_PAGE_STATE	99
#define CH182_STATE_REG		0x19
#define CH182_STATE_MASK	0x00ff
#define CH182_STATE_STUCK	0x0036

struct ch182h_priv {
	unsigned int stuck_polls;
};

static int ch182h_probe(struct phy_device *phydev)
{
	struct ch182h_priv *priv;

	priv = devm_kzalloc(&phydev->mdio.dev, sizeof(*priv), GFP_KERNEL);
	if (!priv)
		return -ENOMEM;
	phydev->priv = priv;
	return 0;
}

static int ch182h_config_init(struct phy_device *phydev)
{
	int ret, bmcr;

	bmcr = phy_read(phydev, MII_BMCR);
	if (bmcr < 0)
		return bmcr;

	/* Already powered down (e.g. resuming from suspend): let the normal
	 * resume path handle it rather than cycling twice.
	 */
	if (bmcr & BMCR_PDOWN)
		bmcr &= ~BMCR_PDOWN;

	ret = phy_write(phydev, MII_BMCR, bmcr | BMCR_PDOWN);
	if (ret < 0)
		return ret;

	usleep_range(CH182H_PDOWN_US, CH182H_PDOWN_US * 2);

	ret = phy_write(phydev, MII_BMCR, bmcr & ~BMCR_PDOWN);
	if (ret < 0)
		return ret;

	msleep(CH182H_PUP_MS);

	return 0;
}

/* Read the vendor state register; leaves page 0 selected. */
static int ch182h_read_state(struct phy_device *phydev)
{
	int ret, state;

	ret = phy_write(phydev, CH182_PAGE_SEL, CH182_PAGE_STATE);
	if (ret < 0)
		return ret;
	state = phy_read(phydev, CH182_STATE_REG);
	ret = phy_write(phydev, CH182_PAGE_SEL, 0);
	if (ret < 0)
		return ret;
	return state;
}

static int ch182h_read_status(struct phy_device *phydev)
{
	struct ch182h_priv *priv = phydev->priv;
	int ret;

	ret = genphy_read_status(phydev);
	if (ret)
		return ret;

	if (phydev->link || phydev->autoneg != AUTONEG_ENABLE) {
		priv->stuck_polls = 0;
		return 0;
	}

	ret = ch182h_read_state(phydev);
	if (ret < 0)
		return ret;
	if ((ret & CH182_STATE_MASK) != CH182_STATE_STUCK) {
		priv->stuck_polls = 0;
		return 0;
	}

	if (++priv->stuck_polls < CH182H_STUCK_POLLS)
		return 0;
	priv->stuck_polls = 0;

	phydev_info(phydev, "stuck without link (state 0x%04x), power cycling\n", ret);

	ret = ch182h_config_init(phydev);
	if (ret)
		return ret;
	return genphy_restart_aneg(phydev);
}

static struct phy_driver ch182h_driver[] = {
	{
		PHY_ID_MATCH_MODEL(PHY_ID_CH182H),
		.name		= "WCH CH182H",
		/* No .features: let phylib read the real abilities from BMSR
		 * (this part reports 10/100 half+full).
		 */
		.probe		= ch182h_probe,
		.config_init	= ch182h_config_init,
		.read_status	= ch182h_read_status,
		.suspend	= genphy_suspend,
		.resume		= genphy_resume,
	},
};

module_phy_driver(ch182h_driver);

static struct mdio_device_id __maybe_unused ch182h_tbl[] = {
	{ PHY_ID_MATCH_MODEL(PHY_ID_CH182H) },
	{ }
};
MODULE_DEVICE_TABLE(mdio, ch182h_tbl);

MODULE_DESCRIPTION("WCH CH182H 10/100 PHY driver");
MODULE_LICENSE("GPL");
