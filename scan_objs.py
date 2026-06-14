import os

data = open('210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg', 'rb').read()

def scan_objects(data, start, max_objects=500):
    pos = start
    results = []
    for i in range(max_objects):
        if pos >= len(data) - 4: break
        lo = data[pos] & 0xFF
        hi = data[pos+1] & 0xFF
        ms_b = lo | (hi << 8)
        if (ms_b & 0x8000) == 0:
            ms_b_val = ms_b & 0x7FFF
            data_start = pos + 2
        else:
            ms_b_val = ms_b & 0x7FFF
            p2 = pos + 2
            shift = 15
            while True:
                lo2 = data[p2] & 0xFF
                hi2 = data[p2+1] & 0xFF
                w = lo2 | (hi2 << 8)
                ms_b_val |= (w & 0x7FFF) << shift
                p2 += 2
                if (w & 0x8000) == 0: break
                if shift > 60: break
                shift += 15
            data_start = p2
        ms = ms_b_val
        if ms < 2 or ms > 20000:
            pos += 1
            continue
        if data_start + ms > len(data):
            pos += 1
            continue
        obj = data[data_start:data_start+ms]
        b0 = obj[0] & 0xFF
        opcode = (b0 >> 6) & 3
        if opcode == 1:
            bits = format(b0, '08b') + format(obj[1], '08b')
            tc = int(bits[2:10], 2)
        elif opcode == 0:
            bits = ''
            for b3 in obj[:3]:
                bits += format(b3, '08b')
            sub = bits[2:18]
            tc = int(sub[:8], 2) | (int(sub[8:16], 2) << 8)
        elif opcode == 2: tc = 0
        else: tc = 256
        results.append((pos, ms, data_start, tc))
        pos = data_start + ms
    return results

for start_pos in [0x52b9, 0x52c5, 0x52d7, 0x52dc, 0x532d]:
    print(f'\n=== Scan from 0x{start_pos:04x} (16-bit LE MS)')
    results = scan_objects(data, start_pos, 30)
    type_counts = {}
    for pos, ms, ds, tc in results:
        type_counts[tc] = type_counts.get(tc, 0) + 1
        name = ''
        if tc == 0x30: name = ' BLOCK_HEADER'
        elif tc == 0x07: name = ' INSERT'
        print(f'  pos=0x{pos:04x} MS={ms:5d} type=0x{tc:02x}({tc:2d}){name}')
    print(f'  Type distribution: {sorted(type_counts.items())[:10]}')
    if not results:
        print(f'  (no objects found)')
