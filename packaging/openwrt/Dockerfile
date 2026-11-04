ARG ROOTFS=openwrt/rootfs:x86-64-openwrt-25.12
FROM ${ROOTFS}
COPY out/Gozar-OpenWrt-x86_64.ipk /tmp/gozar.ipk
RUN opkg install --force-depends /tmp/gozar.ipk
RUN /usr/bin/gozar-core --version && sh -n /etc/init.d/gozar && test -f /etc/config/gozar
CMD ["/bin/sh", "-c", "service gozar start || true; service gozar status || true; ubus call gozar status || true; /usr/bin/gozar-core --version; ping -c 1 -W 3 8.8.8.8"]
