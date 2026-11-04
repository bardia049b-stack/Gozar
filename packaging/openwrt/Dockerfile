ARG ROOTFS=openwrt/rootfs:x86-64-openwrt-24.10
FROM ${ROOTFS}
COPY out/Gozar-OpenWrt-x86_64.ipk /tmp/gozar.ipk
RUN mkdir -p /var/lock /var/run /tmp \
    && cat /etc/opkg.conf \
    && opkg print-architecture \
    && (grep -q '^arch x86_64 ' /etc/opkg.conf || echo 'arch x86_64 10' >> /etc/opkg.conf) \
    && opkg update \
    && opkg install kmod-tun \
    && opkg install /tmp/gozar.ipk
COPY smoke.sh /usr/bin/gozar-smoke
RUN chmod 755 /usr/bin/gozar-smoke && /usr/bin/gozar-core --version && sh -n /etc/init.d/gozar && test -f /etc/config/gozar
CMD ["/bin/sh", "/usr/bin/gozar-smoke"]
