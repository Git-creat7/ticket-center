import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import sharp from 'sharp'

const WIDTH = 1200
const HEIGHT = 900
const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const outputDir = path.resolve(scriptDir, '../public/imgs/events')
const publicDir = path.resolve(scriptDir, '../public')

function svg(body, width = WIDTH, height = HEIGHT, background = '#f6f2e8') {
  return `
    <svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">
      <rect width="${width}" height="${height}" fill="${background}"/>
      ${body}
    </svg>
  `
}

function random(seed) {
  let value = seed >>> 0
  return () => {
    value = (value * 1664525 + 1013904223) >>> 0
    return value / 4294967296
  }
}

function crowd({ seed, count, minY, maxY, colors, scale = 1 }) {
  const next = random(seed)
  const figures = []
  for (let index = 0; index < count; index += 1) {
    const x = Math.round(next() * WIDTH)
    const y = Math.round(minY + next() * (maxY - minY))
    const size = Math.round((16 + next() * 27) * scale)
    const color = colors[Math.floor(next() * colors.length)]
    const skin = ['#6f3c2e', '#a65e42', '#d58d67', '#f0bd8f'][Math.floor(next() * 4)]
    const arm = next() > 0.54
      ? `<path d="M ${x - size * 0.35} ${y + size * 0.25} L ${x - size * 0.72} ${y - size * 0.55}" stroke="${skin}" stroke-width="${Math.max(4, size * 0.16)}" stroke-linecap="round"/>`
      : ''
    figures.push(`
      <g>
        ${arm}
        <circle cx="${x}" cy="${y - size * 0.3}" r="${size * 0.24}" fill="${skin}"/>
        <path d="M ${x - size * 0.38} ${y} Q ${x} ${y - size * 0.14} ${x + size * 0.38} ${y} L ${x + size * 0.48} ${y + size} L ${x - size * 0.48} ${y + size} Z" fill="${color}"/>
      </g>
    `)
  }
  return figures.join('')
}

function concert() {
  const audience = crowd({
    seed: 11,
    count: 75,
    minY: 705,
    maxY: 860,
    colors: ['#f05a47', '#f7c642', '#e9e2d0', '#328fa8', '#263958'],
  })

  return svg(`
    <rect width="1200" height="575" fill="#ef6b52"/>
    <circle cx="1020" cy="130" r="84" fill="#f8cf4a"/>
    <circle cx="1020" cy="130" r="47" fill="#f6f2e8"/>
    <path d="M 0 94 L 310 0 L 500 0 L 0 190 Z" fill="#f3a052"/>
    <path d="M 1200 240 L 910 0 L 730 0 L 1200 345 Z" fill="#e9e2d0" opacity="0.9"/>
    <path d="M 310 655 L 440 170 L 520 170 L 480 655 Z" fill="#f8cf4a"/>
    <path d="M 725 655 L 660 170 L 740 170 L 850 655 Z" fill="#f6f2e8" opacity="0.8"/>
    <g stroke="#253655" stroke-width="18" fill="none">
      <path d="M 205 620 L 270 205 L 930 205 L 995 620"/>
      <path d="M 255 245 L 945 245"/>
      <path d="M 290 205 L 330 245 L 370 205 L 410 245 L 450 205 L 490 245 L 530 205 L 570 245 L 610 205 L 650 245 L 690 205 L 730 245 L 770 205 L 810 245 L 850 205 L 890 245" stroke-width="8"/>
    </g>
    <g fill="#f8cf4a" stroke="#253655" stroke-width="8">
      <circle cx="342" cy="275" r="22"/><circle cx="515" cy="275" r="22"/>
      <circle cx="690" cy="275" r="22"/><circle cx="860" cy="275" r="22"/>
    </g>
    <path d="M 220 620 Q 600 500 980 620 L 1005 735 L 195 735 Z" fill="#263958"/>
    <path d="M 315 610 Q 600 515 885 610 L 885 705 L 315 705 Z" fill="#358da2"/>
    <circle cx="600" cy="482" r="70" fill="#f8cf4a"/>
    <circle cx="600" cy="482" r="35" fill="#ef6b52"/>
    <g fill="#f6f2e8">
      <rect x="545" y="548" width="110" height="142" rx="55"/>
      <circle cx="600" cy="520" r="35"/>
    </g>
    <path d="M 555 580 L 465 514" stroke="#f6f2e8" stroke-width="30" stroke-linecap="round"/>
    <path d="M 646 580 L 735 515" stroke="#f6f2e8" stroke-width="30" stroke-linecap="round"/>
    <path d="M 0 690 Q 225 630 440 695 T 840 688 T 1200 676 L 1200 900 L 0 900 Z" fill="#173f52"/>
    ${audience}
    <path d="M 0 870 Q 250 815 445 875 T 805 865 T 1200 850 L 1200 900 L 0 900 Z" fill="#253655"/>
  `, WIDTH, HEIGHT, '#ef6b52')
}

function theater() {
  return svg(`
    <rect width="1200" height="900" fill="#f1b642"/>
    <rect x="84" y="58" width="1032" height="764" fill="#7c2430"/>
    <rect x="135" y="105" width="930" height="660" fill="#f3d9ad"/>
    <path d="M 135 105 H 1065 V 765 H 135 Z M 254 212 V 681 H 946 V 212 Z" fill="#b9373d" fill-rule="evenodd"/>
    <path d="M 254 212 Q 350 288 445 212 Q 540 288 635 212 Q 730 288 825 212 Q 885 260 946 212 L 946 322 Q 850 380 760 322 Q 665 380 570 322 Q 475 380 380 322 Q 315 365 254 322 Z" fill="#d94c45"/>
    <path d="M 254 212 Q 330 350 302 681 H 450 Q 420 390 500 212 Z" fill="#9f2834"/>
    <path d="M 946 212 Q 870 350 898 681 H 750 Q 780 390 700 212 Z" fill="#9f2834"/>
    <path d="M 302 681 Q 360 590 470 520 Q 535 477 600 438 Q 665 477 730 520 Q 840 590 898 681 Z" fill="#f5c94f"/>
    <ellipse cx="600" cy="687" rx="300" ry="56" fill="#e8a63f"/>
    <path d="M 254 681 H 946 L 1008 765 H 192 Z" fill="#6f2f35"/>
    <g fill="#263a52">
      <circle cx="600" cy="474" r="28"/>
      <path d="M 569 505 Q 600 484 631 505 L 651 628 H 549 Z"/>
      <path d="M 574 530 L 493 586" stroke="#263a52" stroke-width="20" stroke-linecap="round"/>
      <path d="M 626 530 L 707 586" stroke="#263a52" stroke-width="20" stroke-linecap="round"/>
    </g>
    <path d="M 245 149 H 955" stroke="#f1b642" stroke-width="20"/>
    <g fill="#f6e4bf">
      <circle cx="294" cy="149" r="16"/><circle cx="396" cy="149" r="16"/>
      <circle cx="498" cy="149" r="16"/><circle cx="600" cy="149" r="16"/>
      <circle cx="702" cy="149" r="16"/><circle cx="804" cy="149" r="16"/>
      <circle cx="906" cy="149" r="16"/>
    </g>
    <path d="M 84 822 H 1116" stroke="#263a52" stroke-width="38"/>
    <g fill="#263a52">
      <circle cx="190" cy="858" r="48"/><circle cx="310" cy="848" r="55"/>
      <circle cx="445" cy="862" r="49"/><circle cx="575" cy="845" r="58"/>
      <circle cx="715" cy="860" r="50"/><circle cx="845" cy="850" r="57"/>
      <circle cx="995" cy="860" r="51"/>
    </g>
  `, WIDTH, HEIGHT, '#f1b642')
}

function gallery() {
  return svg(`
    <rect width="1200" height="900" fill="#edf0e8"/>
    <path d="M 0 0 H 1200 L 990 210 H 210 Z" fill="#dce4dc"/>
    <path d="M 0 0 L 210 210 V 740 L 0 900 Z" fill="#d7ddd4"/>
    <path d="M 1200 0 L 990 210 V 740 L 1200 900 Z" fill="#c8d7d4"/>
    <path d="M 0 900 L 210 740 H 990 L 1200 900 Z" fill="#e0a64e"/>
    <path d="M 210 210 H 990 V 740 H 210 Z" fill="#faf8ef"/>
    <g fill="#ffffff" stroke="#273c50" stroke-width="15">
      <rect x="284" y="300" width="220" height="275"/>
      <rect x="735" y="278" width="170" height="220"/>
    </g>
    <path d="M 308 518 L 365 355 L 422 474 L 480 330 V 551 H 308 Z" fill="#ef5e4e"/>
    <circle cx="372" cy="392" r="43" fill="#f5ca4b"/>
    <rect x="759" y="302" width="122" height="172" fill="#287f91"/>
    <circle cx="820" cy="361" r="45" fill="#f4a94a"/>
    <path d="M 765 452 L 842 340 L 877 452 Z" fill="#e9e3d2"/>
    <ellipse cx="617" cy="684" rx="174" ry="40" fill="#c28b45"/>
    <rect x="524" y="614" width="186" height="72" fill="#f4f0df"/>
    <path d="M 570 613 C 510 515 553 410 642 430 C 736 451 721 563 662 614 Z" fill="#2c8490"/>
    <circle cx="635" cy="479" r="58" fill="#f3c94a"/>
    <path d="M 620 390 Q 650 326 708 362 Q 745 409 683 445 Q 642 448 620 390 Z" fill="#e75d4e"/>
    <g fill="#304358">
      <circle cx="314" cy="632" r="23"/><path d="M 284 662 Q 314 642 344 662 L 360 770 H 268 Z"/>
      <circle cx="865" cy="606" r="25"/><path d="M 834 637 Q 865 616 897 637 L 914 750 H 817 Z"/>
    </g>
    <path d="M 312 695 L 379 645" stroke="#304358" stroke-width="15" stroke-linecap="round"/>
    <path d="M 866 680 L 790 636" stroke="#304358" stroke-width="15" stroke-linecap="round"/>
    <g fill="#f6cf53">
      <rect x="255" y="163" width="145" height="24"/><rect x="527" y="163" width="145" height="24"/>
      <rect x="800" y="163" width="145" height="24"/>
    </g>
  `, WIDTH, HEIGHT, '#edf0e8')
}

function football() {
  const supporters = crowd({
    seed: 45,
    count: 56,
    minY: 230,
    maxY: 450,
    colors: ['#f4c84a', '#f05f4d', '#f4f0df', '#286f92'],
    scale: 0.64,
  })

  return svg(`
    <rect width="1200" height="900" fill="#6cc3d5"/>
    <circle cx="1025" cy="120" r="63" fill="#f7d24e"/>
    <path d="M 0 155 L 135 120 L 270 155 L 420 110 L 545 155 L 715 115 L 850 155 L 1010 112 L 1200 160 V 235 H 0 Z" fill="#e8f0e9"/>
    <path d="M 0 258 Q 600 70 1200 258 V 570 Q 600 410 0 570 Z" fill="#28465a"/>
    <path d="M 35 290 Q 600 125 1165 290 V 510 Q 600 365 35 510 Z" fill="#f3c84b"/>
    <path d="M 55 340 Q 600 195 1145 340 V 382 Q 600 250 55 382 Z" fill="#e75a4a"/>
    <path d="M 55 425 Q 600 300 1145 425 V 474 Q 600 350 55 474 Z" fill="#f6efe0"/>
    ${supporters}
    <path d="M 0 570 Q 600 410 1200 570 L 1200 900 H 0 Z" fill="#3b9c67"/>
    <path d="M 0 711 Q 600 536 1200 711" fill="none" stroke="#f2edda" stroke-width="12"/>
    <ellipse cx="600" cy="735" rx="170" ry="82" fill="none" stroke="#f2edda" stroke-width="10"/>
    <path d="M 600 524 V 900" stroke="#f2edda" stroke-width="10"/>
    <path d="M 0 824 L 345 719 V 900 H 0 Z" fill="none" stroke="#f2edda" stroke-width="10"/>
    <path d="M 1200 824 L 855 719 V 900 H 1200 Z" fill="none" stroke="#f2edda" stroke-width="10"/>
    <g>
      <circle cx="480" cy="675" r="24" fill="#8b4a35"/><path d="M 446 703 L 512 703 L 525 795 L 430 795 Z" fill="#f2c94c"/>
      <path d="M 466 788 L 430 862 M 493 788 L 525 857" stroke="#263b51" stroke-width="17" stroke-linecap="round"/>
      <circle cx="730" cy="635" r="23" fill="#c87d59"/><path d="M 698 662 L 762 662 L 772 754 L 687 754 Z" fill="#f0f0df"/>
      <path d="M 708 748 L 677 825 M 747 748 L 785 812" stroke="#263b51" stroke-width="17" stroke-linecap="round"/>
      <circle cx="675" cy="791" r="24" fill="#f3eee1" stroke="#263b51" stroke-width="6"/>
      <path d="M 654 790 L 673 771 L 695 786 L 688 812 L 660 814 Z" fill="#263b51"/>
    </g>
  `, WIDTH, HEIGHT, '#6cc3d5')
}

function festival() {
  const audience = crowd({
    seed: 99,
    count: 63,
    minY: 685,
    maxY: 860,
    colors: ['#ee5a48', '#f5c84a', '#f3ede0', '#2c8790', '#38566b'],
    scale: 0.82,
  })

  return svg(`
    <rect width="1200" height="900" fill="#82cbd3"/>
    <circle cx="995" cy="132" r="74" fill="#f6d152"/>
    <path d="M 0 383 Q 178 250 355 383 Q 590 206 790 383 Q 1015 220 1200 375 V 620 H 0 Z" fill="#3d8b69"/>
    <path d="M 0 475 Q 220 354 420 485 Q 650 346 850 478 Q 1035 350 1200 460 V 680 H 0 Z" fill="#79ad66"/>
    <g stroke="#2a4b5b" stroke-width="9">
      <path d="M 105 255 V 455 M 1095 255 V 455"/>
      <path d="M 105 282 Q 600 174 1095 282" fill="none"/>
    </g>
    <g fill="#f5c84a">
      <circle cx="205" cy="253" r="17"/><circle cx="330" cy="226" r="17"/>
      <circle cx="460" cy="207" r="17"/><circle cx="600" cy="201" r="17"/>
      <circle cx="740" cy="207" r="17"/><circle cx="870" cy="226" r="17"/>
      <circle cx="995" cy="253" r="17"/>
    </g>
    <path d="M 302 638 V 388 L 424 300 H 776 L 898 388 V 638 Z" fill="#ea5a49"/>
    <path d="M 424 300 L 600 388 L 776 300 Z" fill="#f5c84a"/>
    <rect x="407" y="397" width="386" height="238" fill="#2b4d5d"/>
    <circle cx="600" cy="498" r="82" fill="#f4c84b"/>
    <path d="M 556 550 Q 600 508 644 550 L 665 635 H 535 Z" fill="#f2ede0"/>
    <circle cx="600" cy="514" r="29" fill="#f2ede0"/>
    <path d="M 568 560 L 510 514 M 632 560 L 690 514" stroke="#f2ede0" stroke-width="20" stroke-linecap="round"/>
    <g>
      <path d="M 75 660 L 176 520 L 277 660 Z" fill="#f2ede0"/>
      <path d="M 176 520 L 176 660 H 277 Z" fill="#f0a34e"/>
      <path d="M 925 660 L 1025 525 L 1125 660 Z" fill="#f2ede0"/>
      <path d="M 1025 525 L 1025 660 H 1125 Z" fill="#e75a49"/>
    </g>
    <path d="M 0 640 Q 220 580 390 648 T 780 645 T 1200 625 L 1200 900 H 0 Z" fill="#398361"/>
    ${audience}
    <path d="M 0 880 Q 300 835 550 884 T 1200 862 V 900 H 0 Z" fill="#2a5d55"/>
  `, WIDTH, HEIGHT, '#82cbd3')
}

function fallback() {
  return svg(`
    <rect width="600" height="450" fill="#ece9de"/>
    <circle cx="510" cy="72" r="45" fill="#f4c94e"/>
    <path d="M 0 285 Q 130 205 255 282 Q 395 188 600 280 V 450 H 0 Z" fill="#4c9a79"/>
    <path d="M 135 334 V 185 L 205 135 H 395 L 465 185 V 334 Z" fill="#ec6350"/>
    <rect x="203" y="195" width="194" height="139" fill="#315568"/>
    <circle cx="300" cy="247" r="43" fill="#f4c94e"/>
    <path d="M 0 350 Q 148 312 278 352 T 600 342 V 450 H 0 Z" fill="#285b58"/>
    <g fill="#f0ece0">
      <circle cx="145" cy="348" r="13"/><circle cx="224" cy="365" r="13"/>
      <circle cx="305" cy="345" r="13"/><circle cx="385" cy="367" r="13"/>
      <circle cx="470" cy="348" r="13"/>
    </g>
  `, 600, 450, '#ece9de')
}

const assets = [
  ['event1.jpg', concert(), WIDTH, HEIGHT, 90],
  ['event2.jpg', theater(), WIDTH, HEIGHT, 90],
  ['event3.jpg', gallery(), WIDTH, HEIGHT, 90],
  ['event4.jpg', football(), WIDTH, HEIGHT, 90],
  ['event5.jpg', festival(), WIDTH, HEIGHT, 90],
  ['fallback.jpg', fallback(), 600, 450, 84],
]

await mkdir(outputDir, { recursive: true })

for (const [filename, artwork, width, height, quality] of assets) {
  const outputPath = path.join(outputDir, filename)
  await sharp(Buffer.from(artwork))
    .resize(width, height, { fit: 'cover' })
    .flatten({ background: '#f6f2e8' })
    .jpeg({ quality, chromaSubsampling: '4:4:4', mozjpeg: true })
    .toFile(outputPath)
  console.log(`Generated ${filename} (${width}x${height})`)
}

const favicon = svg(`
  <rect x="6" y="6" width="52" height="52" rx="10" fill="#2f64ed"/>
  <path d="M 17 23 H 47 V 29 C 43 29 43 35 47 35 V 41 H 17 V 35 C 21 35 21 29 17 29 Z" fill="#ffffff"/>
  <path d="M 28 23 V 41" stroke="#2f64ed" stroke-width="3" stroke-dasharray="3 3"/>
`, 64, 64, '#ffffff')

await sharp(Buffer.from(favicon)).png().toFile(path.join(publicDir, 'favicon.png'))
console.log('Generated favicon.png (64x64)')
