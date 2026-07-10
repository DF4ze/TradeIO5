#!/usr/bin/env python3
"""
Assemble zone_view_v2.html : librairie lightweight-charts inlined + zone_view_v2_data.json inlined
+ nouveau schema visuel (voir export_zones_v2.py pour la philosophie : pas de "force" fabriquee,
vraies metriques au survol, marqueur de 1ere touche, bandeau de regime de marche).

Usage: python3 build_zone_view_v2.py
"""
import json

LWC_PATH = "/tmp/lwc/node_modules/lightweight-charts/dist/lightweight-charts.standalone.production.js"
DATA_PATH = "/sessions/determined-tender-volta/mnt/TradeIO-5/tools/calibration/zone_view_v2_data.json"
OUT_PATH = "/sessions/determined-tender-volta/mnt/TradeIO-5/tools/calibration/zone_view_v2.html"

with open(LWC_PATH) as f:
    lwc_js = f.read()
with open(DATA_PATH) as f:
    data_json = f.read()

n_zones = len(json.loads(data_json)["zones"])

html = """<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="UTF-8">
<title>TradeIO5 — Zones support/resistance (POC v2 — schema honnete)</title>
<style>
  body { margin:0; background:#131722; color:#d1d4dc; font-family: -apple-system, Segoe UI, Roboto, sans-serif; }
  #header { padding: 14px 20px; border-bottom: 1px solid #2a2e39; }
  #header h1 { font-size: 16px; margin: 0 0 4px 0; font-weight: 600; }
  #header p { font-size: 12px; color: #787b86; margin: 3px 0; max-width: 980px; }
  .legend { display:flex; flex-wrap:wrap; gap:18px; margin-top:10px; font-size:12px; }
  .legend span { display:flex; align-items:center; gap:6px; }
  .swatch-grey { width:16px; height:10px; background:rgba(150,150,165,0.14); display:inline-block; }
  .swatch-dot { width:8px; height:8px; border-radius:50%; background:#ff5252; display:inline-block; }
  .swatch-trend { width:16px; height:10px; background:rgba(255,152,0,0.35); display:inline-block; }
  .swatch-range { width:16px; height:10px; background:rgba(33,150,243,0.35); display:inline-block; }
  #chart-container { position: relative; width: 100%; height: 620px; }
  #chart { width:100%; height:100%; }
  .zone-box { position:absolute; pointer-events:none; box-sizing:border-box; z-index: 5;
              background: rgba(150,150,165,0.035); }
  .regime-band { position:absolute; top:0; bottom:0; pointer-events:none; z-index: 1; }
  .touch-marker { position:absolute; width:7px; height:7px; margin-left:-3.5px; margin-top:-3.5px;
                   border-radius:50%; background:#ff5252; box-shadow:0 0 3px rgba(255,82,82,0.9);
                   pointer-events:none; z-index: 6; }
  .zone-label { position:absolute; pointer-events:none; font-size:10.5px; color:#e8eaed; line-height:1.5;
                 background:rgba(19,23,34,0.94); padding:4px 7px; border-radius:3px; white-space:nowrap;
                 z-index: 8; display:none; border:1px solid #2a2e39; }
  #debug { position:absolute; bottom:4px; left:4px; font-size:10px; color:#555; z-index:10; }
</style>
</head>
<body>
<div id="header">
  <h1>BTC/USDT — D1 — Zones support/resistance (technique "consolidation", POC v2)</h1>
  <p>Zones detectees sur historique reel complet (2017-08-17 -&gt; 2026-07-09, __N_ZONES__ zones D1 dedupliquees, non regroupees). Nouveau schema visuel (2026-07-10) suite au retest sur bench elargi : ni la duree de formation, ni le volume, ni la densite de chevauchement ne predisent la fiabilite d'une zone dans le sens attendu -&gt; l'opacite des rectangles est donc UNIFORME (juste pour la lisibilite quand plusieurs zones se superposent), elle ne code plus une "force". Le seul signal robuste trouve est l'effet de 1ere touche (plus faible que les suivantes) -&gt; marque par un point rouge. Le regime de marche (ADX 14) explique mieux la reussite de la technique qu'aucune metrique par zone -&gt; bandeau de fond.</p>
  <div class="legend">
    <span><i class="swatch-grey"></i> Zone consolidation (opacite uniforme, projetee depuis sa detection)</span>
    <span><i class="swatch-dot"></i> 1ere touche post-formation (reaction moyenne -0.58 ATR dans nos tests, plus faible que les suivantes)</span>
    <span><i class="swatch-trend"></i> Regime tendance (ADX&gt;=25)</span>
    <span><i class="swatch-range"></i> Regime range (ADX&lt;20)</span>
  </div>
</div>
<div id="chart-container"><div id="chart"></div><div id="debug"></div></div>
<script>
""" + lwc_js + """
</script>
<script>
const DATA = """ + data_json + """;

const chart = LightweightCharts.createChart(document.getElementById('chart'), {
  autoSize: true,
  layout: { background: { color: '#131722' }, textColor: '#d1d4dc' },
  grid: { vertLines: { color: '#1e222d' }, horzLines: { color: '#1e222d' } },
  timeScale: { borderColor: '#2a2e39', timeVisible: false },
  rightPriceScale: { borderColor: '#2a2e39' },
  crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
});

const candleSeries = chart.addCandlestickSeries({
  upColor: '#26a69a', downColor: '#ef5350', borderVisible: false,
  wickUpColor: '#26a69a', wickDownColor: '#ef5350',
});
candleSeries.setData(DATA.candles);

const overlayContainer = document.getElementById('chart-container');
const debugEl = document.getElementById('debug');

const REGIME_COLOR = {
  trend: 'rgba(255,152,0,0.07)',
  range: 'rgba(33,150,243,0.07)',
};

const regimeEls = DATA.regime_segments
  .filter(s => s.regime === 'trend' || s.regime === 'range')
  .map(s => {
    const band = document.createElement('div');
    band.className = 'regime-band';
    band.style.background = REGIME_COLOR[s.regime];
    overlayContainer.appendChild(band);
    return { seg: s, el: band };
  });

const zoneEls = DATA.zones.map(z => {
  const box = document.createElement('div');
  box.className = 'zone-box';
  const label = document.createElement('div');
  label.className = 'zone-label';
  label.innerHTML = `${Math.round(z.band_low)}-${Math.round(z.band_high)}<br>` +
    `Duree formation: ${Math.round(z.duration_days)}j &nbsp;|&nbsp; Volume: x${z.volume_strength.toFixed(2)} vs base<br>` +
    `Touches observees: ${z.n_touches} &nbsp;|&nbsp; Reaction moyenne: ${z.mean_reaction >= 0 ? '+' : ''}${z.mean_reaction.toFixed(2)} ATR<br>` +
    `Detectee le ${z.anchor_date}`;
  overlayContainer.appendChild(box);
  overlayContainer.appendChild(label);

  let marker = null;
  if (z.first_touch) {
    marker = document.createElement('div');
    marker.className = 'touch-marker';
    marker.title = `1ere touche (${z.first_touch.date}) : ${z.first_touch.reaction >= 0 ? '+' : ''}${z.first_touch.reaction.toFixed(2)} ATR`;
    overlayContainer.appendChild(marker);
  }
  return { zone: z, box, label, marker };
});

function repositionAll() {
  const timeScale = chart.timeScale();
  const containerWidth = overlayContainer.clientWidth;
  let visibleCount = 0;

  regimeEls.forEach(({ seg, el }) => {
    let xLeft = timeScale.timeToCoordinate(seg.start);
    let xRight = timeScale.timeToCoordinate(seg.end);
    if (xLeft === null || xRight === null) { el.style.display = 'none'; return; }
    el.style.display = 'block';
    el.style.left = xLeft + 'px';
    el.style.width = Math.max(xRight - xLeft, 1) + 'px';
  });

  zoneEls.forEach(({ zone, box, label, marker }) => {
    let xLeft = timeScale.timeToCoordinate(zone.anchor_date);
    if (xLeft === null || xLeft === undefined || xLeft < 0) xLeft = 0;
    const yTop = candleSeries.priceToCoordinate(zone.band_high);
    const yBottom = candleSeries.priceToCoordinate(zone.band_low);
    if (yTop === null || yTop === undefined || yBottom === null || yBottom === undefined) {
      box.style.display = 'none';
      if (marker) marker.style.display = 'none';
      return;
    }
    visibleCount++;
    box.style.display = 'block';
    box.style.left = xLeft + 'px';
    box.style.top = yTop + 'px';
    box.style.width = Math.max(containerWidth - xLeft, 0) + 'px';
    box.style.height = Math.max(yBottom - yTop, 2) + 'px';
    label.style.left = (xLeft + 4) + 'px';
    label.style.top = (yTop + 2) + 'px';

    if (marker) {
      const mx = timeScale.timeToCoordinate(zone.first_touch.date);
      const my = candleSeries.priceToCoordinate(zone.first_touch.price);
      if (mx === null || my === null) {
        marker.style.display = 'none';
      } else {
        marker.style.display = 'block';
        marker.style.left = mx + 'px';
        marker.style.top = my + 'px';
      }
    }
  });
  debugEl.textContent = `${visibleCount}/${zoneEls.length} zones positionnees`;
}

chart.subscribeCrosshairMove(param => {
  if (!param || !param.point) {
    zoneEls.forEach(({ label }) => { label.style.display = 'none'; });
    return;
  }
  const { x, y } = param.point;
  zoneEls.forEach(({ box, label }) => {
    if (box.style.display === 'none') { label.style.display = 'none'; return; }
    const left = parseFloat(box.style.left) || 0;
    let hovered = x >= left;
    if (hovered) {
      const top = parseFloat(box.style.top);
      const bottom = top + parseFloat(box.style.height);
      hovered = y >= top && y <= bottom;
    }
    label.style.display = hovered ? 'block' : 'none';
  });
});

chart.timeScale().subscribeVisibleLogicalRangeChange(repositionAll);
new ResizeObserver(() => repositionAll()).observe(overlayContainer);
chart.timeScale().fitContent();
[0, 50, 150, 400, 900, 1800].forEach(delay => setTimeout(repositionAll, delay));
</script>
</body>
</html>
"""

html = html.replace("__N_ZONES__", str(n_zones))

with open(OUT_PATH, "w") as f:
    f.write(html)
print(f"Ecrit: {OUT_PATH} ({len(html)} bytes, {n_zones} zones)")
