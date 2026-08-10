// ===========================================================================
// bus_js.h -- the route picker and label baker, served at /bus.js
// ===========================================================================
// Kept out of sendPage()'s String deliberately. The argument is heap, not
// caching: send_P() streams straight from flash and adds nothing to the page
// buffer, whereas inlining ~10 KB of JS would more than double a String built
// inside loop() on a device where clockEnter also wants a 10 KB CONTIGUOUS
// sprite. The PROGMEM cost is irrelevant against 1.83 MB free.
//
// Everything here runs in the browser, and that is the whole design (see
// docs/decisions.md). Route and stop discovery means matching route numbers to
// variants to stop ids against three different APIs, and the operators
// regenerate variant numbering nightly at 05:00. Doing that on the ESP32 would
// mean shipping a maintenance burden to a device on a wall; doing it here means
// only resolved ids ever reach it.
//
// All three APIs send Access-Control-Allow-Origin: *, which is what makes this
// possible at all. The page is served over http and two of the three APIs are
// https -- that direction is fine, it is only https-page-to-http-resource that
// browsers block.
#pragma once

#include <Arduino.h>
#include <pgmspace.h>

static const char BUS_JS[] PROGMEM = R"JS(
(function () {
  'use strict';

  var KMB = 'http://data.etabus.gov.hk/v1/transport/kmb/';
  var CTB = 'https://rt.data.gov.hk/v2/transport/citybus/';
  var GMB = 'https://data.etagmb.gov.hk/';

  var OPS = [
    { v: 0, label: '九巴 KMB' },
    { v: 3, label: '龍運 Long Win' },
    { v: 1, label: '城巴 Citybus' },
    { v: 2, label: '綠色小巴 Minibus' }
  ];
  var REGIONS = [
    { v: 'HKI', label: '港島 HK Island' },
    { v: 'KLN', label: '九龍 Kowloon' },
    { v: 'NT',  label: '新界 New Territories' }
  ];

  // MAX_H must match LABEL_MAX_H in labels.h -- the device rejects anything
  // taller rather than truncating it.
  //
  // The two widths are the SPACE EACH LABEL ACTUALLY HAS, not the device's
  // buffer cap (labels.h allows 232). They differ because the two lines are
  // budgeted differently:
  //
  //   stop name   x=8..220     the whole line, up to the minutes column
  //   destination x=31..149    after the 往 prefix, before the remark
  //
  // Baking to the buffer cap instead would put a long destination straight
  // through the remark and into the minutes column, and drawBitmap does not
  // clip.
  var MAX_H = 34;
  var STOP_MAX_W = 212, DEST_MAX_W = 118;
  var STOP_PX = 28, DEST_PX = 18;
  // Floors, below which shrinking stops and truncation takes over. Without
  // these a long name silently shrinks until it fits, and "fits" for
  // 洪水橋(洪福邨)總站 (YL900) at 232 px means about 12 px per glyph -- which
  // undoes the entire reason this scene has two big rows instead of four small
  // ones. Better to lose the tail of a name than to lose the name.
  var STOP_MIN_PX = 20, DEST_MIN_PX = 13;
  var FONTS = '"Noto Sans HK","PingFang HK","Microsoft JhengHei","Heiti TC",sans-serif';

  // ---- fetch -------------------------------------------------------------
  // sessionStorage-cached. KMB sends `Cache-Control: max-age=300;` with a
  // trailing semicolon, which is malformed and which browsers are free to
  // ignore -- so the drill-down would re-fetch the same route on every step
  // without this.
  function jget(url, signal) {
    var hit = null;
    try { hit = sessionStorage.getItem(url); } catch (e) {}
    if (hit) return Promise.resolve(JSON.parse(hit));
    return fetch(url, { signal: signal }).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    }).then(function (j) {
      try { sessionStorage.setItem(url, JSON.stringify(j)); } catch (e) {}
      return j;
    });
  }

  // Resolve many stop ids with a bounded number of connections in flight.
  // Unbounded would fire fifty requests at once, which phones throttle and
  // which reads to the user as a hang -- hence the visible progress counter.
  function mapLimit(items, limit, fn, onProgress) {
    return new Promise(function (resolve, reject) {
      var out = new Array(items.length), done = 0, next = 0, running = 0, failed = false;
      function pump() {
        if (failed) return;
        if (done === items.length) return resolve(out);
        while (running < limit && next < items.length) {
          (function (i) {
            running++; next++;
            fn(items[i]).then(function (v) {
              out[i] = v; running--; done++;
              if (onProgress) onProgress(done, items.length);
              pump();
            }, function (e) { failed = true; reject(e); });
          })(next);
        }
      }
      if (!items.length) return resolve(out);
      pump();
    });
  }

  // ---- small DOM helpers -------------------------------------------------
  function el(tag, attrs, text) {
    var e = document.createElement(tag);
    if (attrs) for (var k in attrs) e.setAttribute(k, attrs[k]);
    if (text != null) e.textContent = text;
    return e;
  }
  function select(options, placeholder) {
    var s = el('select');
    s.appendChild(el('option', { value: '' }, placeholder));
    options.forEach(function (o) {
      s.appendChild(el('option', { value: String(o.v) }, o.label));
    });
    return s;
  }

  // ---- 1-bit baking ------------------------------------------------------
  // Canvas rasterisation is NOT deterministic across browsers: different
  // devices have different Hong Kong fonts and different hinting, so the same
  // name baked on a phone and on a desktop will not be pixel-identical. That is
  // harmless, but it is exactly why the preview below shows the packed 1-bit
  // result rather than the antialiased canvas -- what you see is what the panel
  // will draw.
  // The stop-code suffix KMB appends -- "洪水橋(洪福邨)總站 (YL900)" -- is
  // meaningless on a wall clock and is most of what pushes a name past the row.
  // Stripped by default, and the user can put it back by editing the field.
  function cleanName(s) {
    return String(s || '').replace(/\s*\([A-Z]{2}\d{3,}\)\s*$/, '').trim();
  }

  function rasterise(text, px, minPx, maxW) {
    var c = document.createElement('canvas');
    var ctx = c.getContext('2d');
    // Shrink to fit, but only down to the floor -- then truncate. Shrinking
    // without a floor produces something that technically fits and cannot be
    // read from across a room, which is the failure this scene exists to avoid.
    var size = px, m;
    for (;;) {
      ctx.font = size + 'px ' + FONTS;
      m = ctx.measureText(text);
      if (Math.ceil(m.width) <= maxW || size <= (minPx || 12)) break;
      size--;
    }
    if (Math.ceil(m.width) > maxW) {
      while (text.length > 1 && Math.ceil(ctx.measureText(text + '…').width) > maxW)
        text = text.slice(0, -1);
      text += '…';
      m = ctx.measureText(text);
    }
    var w = Math.ceil(m.width);
    var asc  = Math.ceil(m.actualBoundingBoxAscent  || size * 0.88);
    var desc = Math.ceil(m.actualBoundingBoxDescent || size * 0.22);
    var h = Math.min(asc + desc, MAX_H);
    c.width = Math.max(1, Math.min(w, maxW));
    c.height = Math.max(1, h);
    ctx = c.getContext('2d');
    ctx.font = size + 'px ' + FONTS;
    ctx.textBaseline = 'alphabetic';
    ctx.fillStyle = '#fff';
    ctx.fillText(text, 0, asc);
    return ctx.getImageData(0, 0, c.width, c.height);
  }

  // [w, h, packed rows] -- MSB first, each row padded to a whole byte, which is
  // the layout TFT_eSPI::drawBitmap takes directly.
  function pack(img) {
    var w = img.width, h = img.height, d = img.data;
    var rowBytes = (w + 7) >> 3;
    var out = new Uint8Array(2 + rowBytes * h);
    out[0] = w; out[1] = h;
    for (var y = 0; y < h; y++) {
      for (var x = 0; x < w; x++) {
        // alpha > 128 -- the same midpoint the build-time generator uses.
        // Lower fattens every stroke into a blob; higher drops the interior
        // strokes out of dense glyphs first.
        if (d[(y * w + x) * 4 + 3] > 128)
          out[2 + y * rowBytes + (x >> 3)] |= 0x80 >> (x & 7);
      }
    }
    return out;
  }

  function toB64(bytes) {
    var s = '';
    for (var i = 0; i < bytes.length; i += 4096)
      s += String.fromCharCode.apply(null, bytes.subarray(i, i + 4096));
    return btoa(s);
  }

  // Render the PACKED bytes back to a canvas. Deliberately not the source
  // canvas: this is the one place a user can confirm the device will draw what
  // they expect, and showing the pre-threshold version would defeat that.
  function preview(bytes) {
    var w = bytes[0], h = bytes[1], rowBytes = (w + 7) >> 3;
    var c = el('canvas', { 'class': 'prev', width: w, height: h });
    var ctx = c.getContext('2d');
    var img = ctx.createImageData(w, h);
    for (var y = 0; y < h; y++)
      for (var x = 0; x < w; x++) {
        var on = bytes[2 + y * rowBytes + (x >> 3)] & (0x80 >> (x & 7));
        var o = (y * w + x) * 4;
        img.data[o] = img.data[o + 1] = img.data[o + 2] = on ? 255 : 0;
        img.data[o + 3] = 255;
      }
    ctx.putImageData(img, 0, 0);
    return c;
  }

  function upload(slot, which, text) {
    if (!text) return Promise.resolve();
    var bytes = which === 's'
      ? pack(rasterise(text, STOP_PX, STOP_MIN_PX, STOP_MAX_W))
      : pack(rasterise(text, DEST_PX, DEST_MIN_PX, DEST_MAX_W));
    return fetch('/label', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 's=' + slot + '&w=' + which + '&d=' + encodeURIComponent(toB64(bytes))
    }).then(function (r) { return r.ok; });
  }

  // ---- packed value ------------------------------------------------------
  function packStop(o) {
    return [o.op, o.route, o.stopId || '', o.serviceType || 1, o.dir || 'O',
            o.routeId || 0, o.routeSeq || 0, o.stopSeq || 0,
            o.stopTc || '', o.stopEn || '', o.destTc || '', o.destEn || '']
           .map(function (v) { return String(v).split('|').join(' ').trim(); })
           .join('|');
  }

  // ---- one slot's picker -------------------------------------------------
  function Picker(i) {
    this.i = i;
    this.root = document.getElementById('bpick' + i);
    this.input = document.getElementById('bus' + i);
    this.summary = document.getElementById('bsum' + i);
    this.ac = null;
    this.chosen = null;
    this.build();
  }

  Picker.prototype.abort = function () {
    if (this.ac) this.ac.abort();
    this.ac = new AbortController();
    return this.ac.signal;
  };

  Picker.prototype.status = function (msg) {
    this.st.textContent = msg || '';
  };

  Picker.prototype.build = function () {
    var self = this;
    this.root.textContent = '';
    this.opSel = select(OPS, 'Choose operator…');
    this.regSel = select(REGIONS, 'Choose region…');
    this.regSel.style.display = 'none';
    this.routeIn = el('input', { placeholder: 'Route number, e.g. 68X', type: 'text' });
    this.routeIn.style.display = 'none';
    this.varSel = el('div');
    this.stopSel = el('div');
    this.st = el('div', { 'class': 'hint' });
    this.prev = el('div');

    [this.opSel, this.regSel, this.routeIn, this.varSel, this.stopSel,
     this.st, this.prev].forEach(function (n) { self.root.appendChild(n); });

    this.opSel.onchange = function () { self.onOperator(); };
    this.regSel.onchange = function () { self.varSel.textContent = ''; self.stopSel.textContent = ''; };
    this.routeIn.onchange = function () { self.onRoute(); };
  };

  Picker.prototype.onOperator = function () {
    this.varSel.textContent = '';
    this.stopSel.textContent = '';
    this.prev.textContent = '';
    this.status('');
    var op = this.opSel.value;
    this.regSel.style.display = (op === '2') ? '' : 'none';
    this.routeIn.style.display = op === '' ? 'none' : '';
  };

  Picker.prototype.onRoute = function () {
    var route = this.routeIn.value.trim().toUpperCase();
    this.varSel.textContent = '';
    this.stopSel.textContent = '';
    this.prev.textContent = '';
    if (!route) return;
    var op = parseInt(this.opSel.value, 10);
    if (op === 0 || op === 3) this.kmbVariants(op, route);
    else if (op === 1) this.ctbDirections(route);
    else if (op === 2) this.gmbRoutes(route);
  };

  // --- KMB / Long Win
  // There is NO "list variants" endpoint -- /route/68X answers 422 -- so the
  // eight combinations get probed directly. Never test the status code here: a
  // variant that does not exist answers 200 with {"data":{}}, so the only
  // reliable test is whether data.route came back.
  Picker.prototype.kmbVariants = function (op, route) {
    var self = this, signal = this.abort();
    var combos = [];
    ['outbound', 'inbound'].forEach(function (b) {
      for (var st = 1; st <= 4; st++) combos.push({ b: b, st: st });
    });
    this.status('Looking up variants…');
    mapLimit(combos, 6, function (c) {
      return jget(KMB + 'route/' + encodeURIComponent(route) + '/' + c.b + '/' + c.st, signal)
        .then(function (j) {
          var d = j && j.data;
          return (d && d.route) ? { b: c.b, st: c.st, d: d } : null;
        }, function () { return null; });
    }).then(function (rs) {
      var found = rs.filter(Boolean);
      if (!found.length) { self.status('No such route.'); return; }
      self.status(found.length + ' variant(s)');
      // Labelled by destination first, then origin: 68X has four variants and
      // two of them share a destination, so origin is what tells them apart.
      var opts = found.map(function (f, n) {
        return { v: n, label: '往 ' + f.d.dest_tc + '  ← ' + f.d.orig_tc };
      });
      var s = select(opts, 'Choose direction / variant…');
      s.onchange = function () {
        if (s.value === '') return;
        var f = found[parseInt(s.value, 10)];
        self.kmbStops(op, route, f);
      };
      self.varSel.textContent = '';
      self.varSel.appendChild(s);
    });
  };

  Picker.prototype.kmbStops = function (op, route, f) {
    var self = this, signal = this.abort();
    this.stopSel.textContent = '';
    this.status('Loading stops…');
    jget(KMB + 'route-stop/' + encodeURIComponent(route) + '/' + f.b + '/' + f.st, signal)
      .then(function (j) {
        // seq is a String in this endpoint and a Number in the ETA response.
        // A bare .sort() would put stop 10 before stop 2.
        var rows = (j.data || []).slice().sort(function (a, b) { return a.seq - b.seq; });
        return mapLimit(rows, 6, function (r) {
          return jget(KMB + 'stop/' + r.stop, signal).then(function (s) {
            return { id: r.stop, seq: r.seq, tc: s.data.name_tc, en: s.data.name_en };
          });
        }, function (done, total) { self.status(done + ' / ' + total); });
      })
      .then(function (stops) {
        self.status(stops.length + ' stops');
        self.showStops(stops, function (s) {
          return {
            op: op, route: route, stopId: s.id, serviceType: f.st,
            dir: f.b === 'inbound' ? 'I' : 'O',
            stopTc: s.tc, stopEn: s.en,
            destTc: f.d.dest_tc, destEn: f.d.dest_en
          };
        });
      })
      .catch(function (e) { if (e.name !== 'AbortError') self.status('Lookup failed.'); });
  };

  // --- Citybus
  Picker.prototype.ctbDirections = function (route) {
    var self = this, signal = this.abort();
    this.status('Looking up route…');
    jget(CTB + 'route/CTB/' + encodeURIComponent(route), signal).then(function (j) {
      var d = j && j.data;
      if (!d || !d.route) { self.status('No such route.'); return; }
      self.status('');
      var opts = [
        { v: 'outbound', label: '往 ' + d.dest_tc + '  ← ' + d.orig_tc },
        { v: 'inbound',  label: '往 ' + d.orig_tc + '  ← ' + d.dest_tc }
      ];
      var s = select(opts, 'Choose direction…');
      s.onchange = function () {
        if (s.value === '') return;
        var out = s.value === 'outbound';
        self.ctbStops(route, s.value, out ? d.dest_tc : d.orig_tc, out ? d.dest_en : d.orig_en);
      };
      self.varSel.textContent = '';
      self.varSel.appendChild(s);
    }, function () { self.status('Lookup failed.'); });
  };

  Picker.prototype.ctbStops = function (route, dirWord, destTc, destEn) {
    var self = this, signal = this.abort();
    this.stopSel.textContent = '';
    this.status('Loading stops…');
    jget(CTB + 'route-stop/CTB/' + encodeURIComponent(route) + '/' + dirWord, signal)
      .then(function (j) {
        var rows = (j.data || []).slice().sort(function (a, b) { return a.seq - b.seq; });
        return mapLimit(rows, 6, function (r) {
          return jget(CTB + 'stop/' + r.stop, signal).then(function (s) {
            return { id: r.stop, seq: r.seq, tc: s.data.name_tc, en: s.data.name_en };
          });
        }, function (done, total) { self.status(done + ' / ' + total); });
      })
      .then(function (stops) {
        self.status(stops.length + ' stops');
        self.showStops(stops, function (s) {
          return {
            op: 1, route: route, stopId: s.id,
            dir: dirWord === 'inbound' ? 'I' : 'O',
            stopTc: s.tc, stopEn: s.en, destTc: destTc, destEn: destEn
          };
        });
      })
      .catch(function (e) { if (e.name !== 'AbortError') self.status('Lookup failed.'); });
  };

  // --- Green minibus
  // Region first, and it is not optional: route numbers are only unique WITHIN
  // a region -- "12" exists in both Kowloon and the New Territories -- so a
  // route code without its region silently picks the wrong route.
  Picker.prototype.gmbRoutes = function (route) {
    var self = this, signal = this.abort();
    var region = this.regSel.value;
    if (!region) { this.status('Choose a region first.'); return; }
    this.status('Looking up route…');
    jget(GMB + 'route/' + region + '/' + encodeURIComponent(route), signal).then(function (j) {
      var rs = (j && j.data) || [];
      if (!rs.length) { self.status('No such route in that region.'); return; }
      var opts = [], flat = [];
      rs.forEach(function (r) {
        (r.directions || []).forEach(function (dir) {
          flat.push({ r: r, dir: dir });
          opts.push({
            v: flat.length - 1,
            label: '往 ' + dir.dest_tc + '  ← ' + dir.orig_tc +
                   (rs.length > 1 ? '  (' + r.description_tc + ')' : '')
          });
        });
      });
      self.status('');
      var s = select(opts, 'Choose direction…');
      s.onchange = function () {
        if (s.value === '') return;
        var f = flat[parseInt(s.value, 10)];
        self.gmbStops(route, f);
      };
      self.varSel.textContent = '';
      self.varSel.appendChild(s);
    }, function () { self.status('Lookup failed.'); });
  };

  Picker.prototype.gmbStops = function (route, f) {
    var self = this, signal = this.abort();
    this.stopSel.textContent = '';
    this.status('Loading stops…');
    // This endpoint carries name_tc directly, so unlike KMB and Citybus there
    // is no per-stop resolution step at all.
    jget(GMB + 'route-stop/' + f.r.route_id + '/' + f.dir.route_seq, signal)
      .then(function (j) {
        var rows = ((j.data || {}).route_stops || []).slice()
                     .sort(function (a, b) { return a.stop_seq - b.stop_seq; });
        self.status(rows.length + ' stops');
        self.showStops(rows.map(function (r) {
          return { id: r.stop_id, seq: r.stop_seq, tc: r.name_tc, en: r.name_en };
        }), function (s) {
          return {
            op: 2, route: route,
            routeId: f.r.route_id, routeSeq: f.dir.route_seq, stopSeq: s.seq,
            stopTc: s.tc, stopEn: s.en,
            destTc: f.dir.dest_tc, destEn: f.dir.dest_en
          };
        });
      })
      .catch(function (e) { if (e.name !== 'AbortError') self.status('Lookup failed.'); });
  };

  // --- shared stop list + commit
  Picker.prototype.showStops = function (stops, toStop) {
    var self = this;
    var opts = stops.map(function (s, n) { return { v: n, label: s.seq + '. ' + s.tc }; });
    var sel = select(opts, 'Choose stop…');
    sel.onchange = function () {
      if (sel.value === '') return;
      self.choose(toStop(stops[parseInt(sel.value, 10)]));
    };
    this.stopSel.textContent = '';
    this.stopSel.appendChild(sel);
  };

  // Committing a stop opens the two label fields, pre-filled with the operator's
  // own names minus the stop-code suffix. They are editable because the operator
  // names are written for a route database, not for a wall: nobody needs
  // "洪水橋(洪福邨)總站" when "洪水橋" is the stop they mean, and only the person
  // living with the display knows which shorthand they read instantly.
  Picker.prototype.choose = function (o) {
    var self = this;
    o.stopTc = cleanName(o.stopTc);
    o.destTc = cleanName(o.destTc);
    this.chosen = o;

    this.prev.textContent = '';
    var stopIn = el('input', { type: 'text', maxlength: '24' });
    var destIn = el('input', { type: 'text', maxlength: '24' });
    stopIn.value = o.stopTc;
    destIn.value = o.destTc;

    this.prev.appendChild(el('label', null, 'Stop name on the display'));
    this.prev.appendChild(stopIn);
    this.prev.appendChild(el('label', null, 'Destination on the display'));
    this.prev.appendChild(destIn);
    this.prev.appendChild(el('div', { 'class': 'hint' },
      'Exactly as the panel will draw it — 1-bit, at the real size. Shorten a '
      + 'name if it renders too small to read from where the clock hangs.'));
    var box = el('div');
    this.prev.appendChild(box);

    function refresh() {
      self.chosen.stopTc = stopIn.value.trim();
      self.chosen.destTc = destIn.value.trim();
      self.input.value = packStop(self.chosen);
      self.summary.textContent = self.chosen.stopTc + '  —  ' + o.route
                               + ' 往 ' + self.chosen.destTc;
      box.textContent = '';
      if (self.chosen.stopTc)
        box.appendChild(preview(pack(rasterise(self.chosen.stopTc, STOP_PX, STOP_MIN_PX, STOP_MAX_W))));
      if (self.chosen.destTc)
        box.appendChild(preview(pack(rasterise(self.chosen.destTc, DEST_PX, DEST_MIN_PX, DEST_MAX_W))));
    }
    stopIn.oninput = refresh;
    destIn.oninput = refresh;
    refresh();
  };

  // ---- wiring ------------------------------------------------------------
  // Counted from the page rather than hard-coded: the number of slots is
  // BUS_SLOTS in settings.h, and a second copy of it here would be a second
  // place to forget.
  var pickers = [];
  for (var i = 0; document.getElementById('bpick' + i); i++) pickers.push(new Picker(i));

  // Bake and upload before the form navigates away -- the device restarts
  // moments after /save, so there is no "after".
  var form = document.querySelector('form[action="/save"]');
  if (form) {
    form.addEventListener('submit', function (ev) {
      var pending = pickers.filter(function (p) { return p.chosen; });
      if (!pending.length) return;               // nothing rebaked; save as-is
      ev.preventDefault();
      Promise.all(pending.map(function (p) {
        return Promise.all([upload(p.i, 's', p.chosen.stopTc),
                            upload(p.i, 'd', p.chosen.destTc)]);
      })).then(function () { form.submit(); },
               function () { form.submit(); });  // save the ids even if baking
    });                                          // failed; English still shows
  }

  // AP mode has no route to the internet BY CONSTRUCTION -- the device is the
  // access point. Saying so matters: a first-time user who sees the picker fail
  // will otherwise conclude the whole setup is broken.
  // In AP mode the whole bus section is omitted server-side, so there is
  // nothing here to drive and -- more to the point -- nothing to probe the
  // network for. Bailing out is what keeps this script from firing a doomed
  // request every time somebody opens the setup portal.
  var note = document.getElementById('bne');
  if (!note) return;

  jget(GMB + 'route/HKI/1').then(function () {
    note.textContent = 'Pick a route below, or type the packed value under "Manual entry".';
  }, function () {
    note.innerHTML = '路線查詢需要互聯網 &mdash; ' +
      'route lookup needs internet access, and this device’s setup network ' +
      'has none. <b>Saved stops are kept</b>: you can save now and add stops ' +
      'later from your home network, or use "Manual entry".';
    pickers.forEach(function (p) { p.root.style.display = 'none'; });
  });
})();
)JS";
