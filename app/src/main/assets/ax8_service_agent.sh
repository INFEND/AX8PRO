#!/bin/sh
# AX8 PRO: opt-in, router-local service restriction agent. Never modify INPUT,
# DNS configuration, boot files, existing QoS or foreign firewall chains.
set -f
ROOT=/tmp/ax8pro-services
[ "$1" = "--test-root" ] && ROOT="$2"
umask 077
cd "$ROOT" || exit 1
write_status() { printf '%s\n' "$1" > status.new && mv status.new status; }
detach() {
    tool="$1"; chain="$2"
    while "$tool" -D FORWARD -i br0 -j "$chain" >/dev/null 2>&1; do :; done
    if "$tool" -L "$chain" -n >/dev/null 2>&1; then
        "$tool" -F "$chain" >/dev/null 2>&1 && "$tool" -X "$chain" >/dev/null 2>&1 || return 1
    fi
}
cleanup() {
    slot="$1"
    detach iptables "AX8SVC$slot" || return 1
    if command -v ip6tables >/dev/null 2>&1; then detach ip6tables "AX8SVC$slot" || return 1; fi
    if command -v ipset >/dev/null 2>&1; then
        for setname in $(ipset list -n 2>/dev/null); do
            case "$setname" in ax8svc_"$slot"_*) ipset destroy "$setname" >/dev/null 2>&1 || return 1;; esac
        done
    fi
    (set +f; rm -f "$slot".addr.*)
}
# Only DNS answers after Name: (never the resolver address).
answers() {
    awk -v family="$1" '
      /^Name:[ \t]/ { seen=1; next }
      seen && /^[ \t]*Address([ \t]+[0-9]+)?:[ \t]/ {
        for(i=1;i<=NF;i++){
          s=$i
          if(family==4 && s ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$/){
            split(s,a,".");ok=1
            for(j=1;j<=4;j++)if(a[j]>255)ok=0
            if(a[1]==0||a[1]==10||a[1]==127||a[1]>=224||
               (a[1]==169&&a[2]==254)||(a[1]==172&&a[2]>=16&&a[2]<=31)||
               (a[1]==192&&a[2]==168)||(a[1]==100&&a[2]>=64&&a[2]<=127))ok=0
            if(ok)print s
          }
          if(family==6 && s ~ /^[23][0-9a-fA-F:]+$/ && s ~ /:/)print s
        }
      }' | sort -u
}
same_revision() { [ "$(head -n 1 plan 2>/dev/null)" = "$rev" ]; }
# DNS lookup bounded even on firmware without timeout or standalone nslookup.
lookup() {
    if command -v nslookup >/dev/null 2>&1; then
        set -- nslookup "$@"
    else
        set -- busybox nslookup "$@"
    fi
    if command -v timeout >/dev/null 2>&1; then
        timeout 5 "$@"
    else
        "$@" & query=$!
        (sleep 5; kill "$query" 2>/dev/null) & timer=$!
        wait "$query"; result=$?
        kill "$timer" 2>/dev/null
        wait "$timer" 2>/dev/null
        return "$result"
    fi
}
build_service() {
    id="$1"; shift
    set4="ax8svc_"$new"_4_"$id
    set6="ax8svc_"$new"_6_"$id
    file4="$new.addr.4.$id"
    file6="$new.addr.6.$id"
    if [ -f "$file4" ]; then return 0; fi
    : > "$file4"; : > "$file6"
    for host in "$@"; do
        same_revision || return 1
        echo "$host" | grep -Eq '^[a-z0-9][a-z0-9.-]*\.[a-z0-9]+$' || return 1
        ips4=$(lookup "$host" 2>/dev/null | answers 4)
        ips6=
        if [ "$v6" = 1 ]; then ips6=$(lookup -type=AAAA "$host" 2>/dev/null | answers 6); fi
        [ -n "$ips4$ips6" ] || missing=$((missing+1))
        for addr in $ips4; do printf '%s\n' "$addr" >> "$file4"; done
        for addr in $ips6; do printf '%s\n' "$addr" >> "$file6"; done
    done
    sort -u "$file4" > "$file4.new" && mv "$file4.new" "$file4" || return 1
    sort -u "$file6" > "$file6.new" && mv "$file6.new" "$file6" || return 1
    [ -s "$file4" ] || [ -s "$file6" ] || return 1
    if [ "$backend" = ipset ]; then
        ipset create "$set4" hash:ip family inet timeout 1800 maxelem 4096 || return 1
        for addr in $(cat "$file4"); do ipset -exist add "$set4" "$addr" || return 1; done
        if [ "$v6" = 1 ]; then
            ipset create "$set6" hash:ip family inet6 timeout 1800 maxelem 4096 || return 1
            for addr in $(cat "$file6"); do ipset -exist add "$set6" "$addr" || return 1; done
        fi
    fi
}
add_rule() {
    tool="$1"; scope="$2"; setname="$3"; family="$4"; service="$5"
    if [ "$backend" = ipset ]; then
        if [ "$scope" = ALL ]; then
            "$tool" -A "AX8SVC$new" -m set --match-set "$setname" dst -j DROP
        else
            "$tool" -A "AX8SVC$new" -m mac --mac-source "$scope" -m set --match-set "$setname" dst -j DROP
        fi
        return $?
    fi
    for addr in $(cat "$new.addr.$family.$service"); do
        entries=$((entries+1))
        [ "$entries" -le 512 ] || return 1
        if [ "$scope" = ALL ]; then
            "$tool" -A "AX8SVC$new" -d "$addr" -j DROP || return 1
        else
            "$tool" -A "AX8SVC$new" -m mac --mac-source "$scope" -d "$addr" -j DROP || return 1
        fi
    done
    return 0
}
reconcile_backend() {
    backend="$1"
    entries=0
    cp plan snapshot || return 1
    rev=$(head -n 1 snapshot)
    case "$rev" in ''|*[!a-f0-9-]*) return 1;; esac
    old=$(cat active 2>/dev/null)
    case "$old" in A) new=B;; B) new=A;; *) old=;new=A;; esac
    # Empty desired plan must remove both slots without any DNS dependency.
    if [ "$(wc -l < snapshot)" -le 1 ]; then
        cleanup A && cleanup B || { write_status "ERROR|$rev|0|Не удалось снять цепочки"; return 1; }
        rm -f active
        write_status "OFF|$rev|0|Ограничения сняты"; return 0
    fi
    write_status "WORK|$rev|0|Подготовка DNS и правил"
    v6=0
    if command -v ip6tables >/dev/null 2>&1 && ip6tables -L FORWARD -n >/dev/null 2>&1; then v6=1; fi
    missing=0;count=0
    if ! cleanup "$new"; then write_status "ERROR|$rev|0|Не удалось очистить резервную цепочку";return 1;fi
    if ! iptables -N "AX8SVC$new"; then return 1;fi
    if [ "$v6" = 1 ] && ! ip6tables -N "AX8SVC$new"; then cleanup "$new";return 1;fi
    bad=0
    {
        IFS= read -r ignored
        while read -r scope id hosts; do
            [ -n "$scope" ] || continue
            case "$id" in ''|*[!a-z0-9]*) bad=1;break;; esac
            if [ "$scope" != ALL ] && ! echo "$scope" | grep -Eq '^([0-9A-F]{2}:){5}[0-9A-F]{2}$'; then bad=1;break;fi
            count=$((count+1)); [ "$count" -le 128 ] || { bad=1;break; }
            # Word splitting here is intentional: validated exact DNS names.
            if ! build_service "$id" $hosts || ! add_rule iptables "$scope" "ax8svc_"$new"_4_"$id 4 "$id"; then bad=1;break;fi
            if [ "$v6" = 1 ] && ! add_rule ip6tables "$scope" "ax8svc_"$new"_6_"$id 6 "$id"; then bad=1;break;fi
        done
    } < snapshot
    if [ "$bad" = 1 ] || [ "$count" = 0 ] || ! same_revision; then
        cleanup "$new";write_status "ERROR|$rev|0|DNS или фильтр недоступен, прежние правила сохранены";return 1
    fi
    if ! iptables -I FORWARD 1 -i br0 -j "AX8SVC$new"; then cleanup "$new";return 1;fi
    if [ "$v6" = 1 ] && ! ip6tables -I FORWARD 1 -i br0 -j "AX8SVC$new"; then cleanup "$new";return 1;fi
    # Commit the new slot before removing the old one, allowing safe restart.
    if ! printf '%s\n' "$new" > active.new || ! mv active.new active; then cleanup "$new";return 1;fi
    if [ -n "$old" ] && ! cleanup "$old"; then write_status "ERROR|$rev|$count|Новые правила записаны, не снята старая цепочка";return 2;fi
    write_status "INSTALLED|$rev|$count|IPv4=1 IPv6=$v6; режим=$backend; неразрешённых имён=$missing; IP-фильтр, не проверка реального трафика"
}
reconcile() {
    # ipset is optional. The direct backend works with ordinary iptables -d.
    if command -v ipset >/dev/null 2>&1 && ipset list -n >/dev/null 2>&1; then
        reconcile_backend ipset
        rc=$?
        [ "$rc" = 0 ] && return 0
        [ "$rc" = 2 ] && return 1
        # Do not retry a plan that changed while resolving DNS.
        same_revision || return 1
    fi
    reconcile_backend direct
}
if [ "$3" = "--once" ]; then reconcile; exit $?; fi
# This is a single detached worker. App never terminates a live unrelated PID.
printf '%s\n' "$$" > pid
trap 'rm -f pid; exit 0' TERM INT
last=;next=0
while :; do
    wanted=$(head -n 1 plan 2>/dev/null)
    now=$(date +%s)
    if [ -n "$wanted" ] && { [ "$wanted" != "$last" ] || [ "$now" -ge "$next" ]; }; then
        if reconcile; then next=$(( $(date +%s) + 120 ));else
            current=$(cat status 2>/dev/null)
            case "$current" in ERROR*) :;; *) write_status "ERROR|$wanted|0|Ошибка установки правил";; esac
            next=$(( $(date +%s) + 30 ))
        fi
        last="$wanted"
    fi
    sleep 3
done
