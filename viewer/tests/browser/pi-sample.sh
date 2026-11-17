#!/usr/bin/env bash
# Runs ON the Pi (piped over ssh): samples camcast-viewer and the system every STEP seconds for DURATION seconds.
# CSV: t,viewers,fps,svc_cpu_pct (of one core),svc_rss_mb,sys_cpu_pct (all 4 cores),mem_avail_mb,load1,temp_c
DURATION=${1:-200}
STEP=${2:-5}
UNIT=camcast-viewer.service
cpu_ns() { systemctl show -p CPUUsageNSec --value $UNIT; }
sys_busy() { awk '/^cpu /{print $2+$3+$4+$7+$8, $2+$3+$4+$5+$6+$7+$8}' /proc/stat; }
echo "t,viewers,fps,svc_cpu_pct,svc_rss_mb,sys_cpu_pct,mem_avail_mb,load1,temp_c"
prev_ns=$(cpu_ns); read -r prev_busy prev_total < <(sys_busy); start=$(date +%s)
while [ $(( $(date +%s) - start )) -lt "$DURATION" ]; do
  sleep "$STEP"
  ns=$(cpu_ns); read -r busy total < <(sys_busy)
  svc=$(awk -v a="$prev_ns" -v b="$ns" -v s="$STEP" 'BEGIN{printf "%.1f", (b-a)/1e7/s}')
  sys=$(awk -v a="$prev_busy" -v b="$busy" -v c="$prev_total" -v d="$total" 'BEGIN{printf "%.1f", 100*(b-a)/(d-c)}')
  # MemoryCurrent needs the memory cgroup, which Raspberry Pi OS disables; read the process RSS instead.
  pid=$(systemctl show -p MainPID --value $UNIT)
  mem=$(awk '/VmRSS/{printf "%.1f", $2/1024}' /proc/"$pid"/status 2>/dev/null)
  avail=$(awk '/MemAvailable/{printf "%d", $2/1024}' /proc/meminfo)
  status=$(curl -s --max-time 2 http://127.0.0.1:8091/api/status)
  viewers=$(sed -E 's/.*"viewers": ([0-9]+).*/\1/' <<<"$status")
  fps=$(sed -E 's/.*"fps": ([0-9.]+).*/\1/' <<<"$status")
  temp=$(awk '{printf "%.1f", $1/1000}' /sys/class/thermal/thermal_zone0/temp)
  echo "$(( $(date +%s) - start )),$viewers,$fps,$svc,$mem,$sys,$avail,$(cut -d' ' -f1 /proc/loadavg),$temp"
  prev_ns=$ns; prev_busy=$busy; prev_total=$total
done
