'use strict';
/* Pixel-aligned skin UVs; slim arms are 3 pixels, classic arms are 4. */
(() => {
  const fallback = document.createElement('canvas'); fallback.width = fallback.height = 64;
  const paint = fallback.getContext('2d');
  paint.fillStyle='#b58b67'; paint.fillRect(0,0,64,32); paint.fillRect(32,48,16,16);
  paint.fillStyle='#325c51'; paint.fillRect(16,16,24,16); paint.fillRect(40,16,16,7); paint.fillRect(32,48,16,7);
  paint.fillStyle='#354863'; paint.fillRect(0,16,16,16); paint.fillRect(16,48,16,16);
  paint.fillStyle='#543f33'; paint.fillRect(0,0,32,8); paint.fillRect(8,8,8,2); paint.fillRect(8,10,1,2); paint.fillRect(15,10,1,2);
  paint.fillStyle='#e8e9ec'; paint.fillRect(9,12,2,1); paint.fillRect(13,12,2,1); paint.fillStyle='#438caf'; paint.fillRect(10,12,1,1); paint.fillRect(13,12,1,1);
  paint.fillStyle='#805942'; paint.fillRect(11,14,3,1);
  let texture = fallback, targetTilt = 0, tilt = 0, nextGesture = 0;
  const api = window.LiteSkin = {model:'classic',motion:true, load(base64) { return new Promise((resolve,reject) => { const image = new Image(); image.onload = () => { if (image.width!==64 || ![32,64].includes(image.height)) return reject(new Error('Invalid skin dimensions')); texture=image; resolve(); }; image.onerror = () => reject(new Error('Invalid skin PNG')); image.src='data:image/png;base64,'+base64; }); }};
  function rectangle(c, uv, x, y, w, h, overlay) {
    if (texture === fallback && overlay) return;
    c.drawImage(texture,uv[0],uv[1],uv[2],uv[3],x,y,w,h);
  }
  function part(c,x,y,w,h,uv,layer,angle=0) {
    c.save(); c.translate(x+w/2,y); c.rotate(angle); x=-w/2;
    // Keep a one-pixel silhouette behind each part without shifting the skin
    // UV. The old x+2 fill ate most of a 3px Alex arm and made it look broken.
    c.fillStyle='#162328'; c.fillRect(x-.18,.18,w+.36,h+.36);
    rectangle(c,uv,x,0,w,h,false);
    c.fillStyle='rgba(0,0,0,.09)'; c.fillRect(x+w-2,0,2,h);
    if (texture.height===64 || layer[1]<16) rectangle(c,layer,x-.4,-.4,w+.8,h+.8,true);
    c.restore();
  }
  function render(canvas,time) {
    const c=canvas.getContext('2d'); const width=canvas.width,height=canvas.height; c.clearRect(0,0,width,height); c.imageSmoothingEnabled=false;
    const scale=Math.min(width/30,height/39); c.save(); c.translate(width/2,height*.12); c.scale(scale,scale);
    c.fillStyle='rgba(74,159,118,.16)'; c.beginPath(); c.ellipse(0,32.6,9.5,1.7,0,0,Math.PI*2); c.fill();
    c.strokeStyle='rgba(138,234,190,.2)'; c.lineWidth=.12; c.beginPath(); c.ellipse(0,32.6,11,2.5,0,0,Math.PI*2); c.stroke();
    const moving=api.motion&&!window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const breathing=moving?Math.sin(time*.0014)*.08:0, swing=moving?Math.sin(time*.0008)*.018:0;
    c.translate(0,breathing); const arm=api.model==='slim'?3:4; const modern=texture.height===64;
    part(c,-4,20,4,12,[4,20,4,12],[4,36,4,12],swing*.35);
    part(c,0,20,4,12,modern?[20,52,4,12]:[4,20,4,12],[4,52,4,12],-swing*.35);
    part(c,-4-arm,8,arm,12,[44,20,arm,12],[44,36,arm,12],swing+.012);
    part(c,4,8,arm,12,modern?[36,52,arm,12]:[44,20,arm,12],[52,52,arm,12],-swing-.012);
    part(c,-4,8,8,12,[20,20,8,12],[20,36,8,12]);
    part(c,-4,0,8,8,[8,8,8,8],[40,8,8,8],moving?tilt:0); c.restore();
  }
  function frame(time) {
    if (!document.hidden) {
      if(time>nextGesture){targetTilt=(Math.random()-.5)*.045;nextGesture=time+4500+Math.random()*3500;}
      tilt+=(targetTilt-tilt)*.015;
      document.querySelectorAll('canvas[id^="skin-"]').forEach(canvas => {if(canvas.offsetParent!==null)render(canvas,time);});
    }
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);
})();
