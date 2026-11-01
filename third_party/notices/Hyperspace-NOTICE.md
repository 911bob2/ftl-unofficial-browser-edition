# FTL Hyperspace (Hyperspace.ftl, redistributed unmodified)

Servers running this project can hand signed-in players an unmodified copy of `Hyperspace.ftl` from the official
FTL Hyperspace release **v1.23.2** (the Mods panel's "our server" source). The file is not in this repository or in
the application image; an operator places it in the server's mods directory, and the server checks it against the
pinned SHA-256 below before offering it.

- Project: FTL Hyperspace, https://github.com/FTL-Hyperspace/FTL-Hyperspace
- Release: https://github.com/FTL-Hyperspace/FTL-Hyperspace/releases/tag/v1.23.2
- Source code of this exact release: tag `v1.23.2`, commit `db570d728321a5a2c70add0988153373fd4ec08a`
  (https://github.com/FTL-Hyperspace/FTL-Hyperspace/tree/db570d728321a5a2c70add0988153373fd4ec08a)
- Release archive: `FTL.Hyperspace.1.23.2-Windows.zip`, 15,171,493 bytes,
  SHA-256 `4f3a87bd9e6dd2efc9fa1505e77bcd05bb31ed711ffe88c6167d4cde50dd7a8b`
- Redistributed file: `Hyperspace.ftl` from that archive, 9,950,190 bytes,
  SHA-256 `fa8205d5be603a4f01e1fc081097d1cb24b5a13588e403e65e6ff3a4f5173fa8`
- Licence: GNU General Public License v3.0 or later ([GPL-3.0.txt](GPL-3.0.txt)), as stated by the project's
  `COPYRIGHT.md` (reproduced below from commit `1b05a07a750f5f0ba9ace37ae1c58bd6bc5a04ea`). The `LICENSE.md` shipped
  at the v1.23.2 tag itself names Creative Commons Attribution-ShareAlike 4.0
  (https://creativecommons.org/licenses/by-sa/4.0/); the project moved to GPL-3.0-or-later after that release.
  The file is passed on unchanged, with this attribution, the licence text and the source link above.

The browser engine of this project does not run Hyperspace's code; it reimplements Hyperspace behaviour (see the
Hyperspace row in NOTICE.md). `Hyperspace.ftl` contains Hyperspace's data files (XML, images, Lua scripts).

## Credits (release README and mod metadata)

Development: TheSwiftTiger, Amyseni (Winderps), ih8ih8sn0w, Admiral Billy, laszlogasd, mathchamp93, Mr. Doom,
Chrono Vortex, Pepson, ranhai, sillysandvich, The Dumb Dino, ZeroFreed. Art: SaltyFriedRice.

Special thanks: _Kilburn, Qaser7, slow (slowriderxcorps), Masala, bamalf, Puffias Leroy McBillington III
(KingdomKrafters/Kix), BlizzArchonJ (mr_easy_money), Paradigm, FTL Multiverse Team.

## Hyperspace COPYRIGHT.md (verbatim)

FTL Hyperspace is licensed under `GPL-3.0-or-later`, see [LICENSE](LICENSE). 
The source code of official releases is available at https://github.com/FTL-Hyperspace/FTL-Hyperspace/releases. Modified versions must provide their own source code.

The components below keep their original licenses. Modifications made to them by the Hyperspace team are licensed under `GPL-3.0-or-later` as part of Hyperspace.

### ZHL
MIT License
```
Copyright (c) 2016 _Kilburn

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

ZHL files:
- [`src/zhl/zhl.cpp`](src/zhl/zhl.cpp)
- [`src/zhl/zhl.h`](src/zhl/zhl.h)
- [`src/zhl/zhl_private.h`](src/zhl/zhl_private.h)
- [`src/zhl/zhl_internal.h`](src/zhl/zhl_internal.h)
- [`src/zhl/SigScan.cpp`](src/zhl/SigScan.cpp)
- [`src/zhl/SigScan.h`](src/zhl/SigScan.h)
- [`libzhlgen/lua/parsefuncs.lua`](libzhlgen/lua/parsefuncs.lua)
- [`libzhlgen/lua/cparser.lua`](libzhlgen/lua/cparser.lua)
- [`libzhlgen/test/IDA Formatting.txt`](<libzhlgen/test/IDA Formatting.txt>)

### RapidXML
MIT License
```
Copyright (C) 2006, 2009 Marcin Kalicinski

Permission is hereby granted, free of charge, to any person obtaining a copy 
of this software and associated documentation files (the "Software"), to deal 
in the Software without restriction, including without limitation the rights 
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies 
of the Software, and to permit persons to whom the Software is furnished to do so, 
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all 
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR 
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, 
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL 
THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER 
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, 
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS 
IN THE SOFTWARE.
```

### Discord RPC
MIT License
```
Copyright 2017 Discord, Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Mologie Detours
MIT License
```
Copyright (c) 2011 Oliver Kuckertz <oliver.kuckertz@mologie.de>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

### HDE32 & HDE64
BSD 2-Clause License
```
Copyright (c) 2008-2009, Vyacheslav Patkov.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

 1. Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
 2. Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE REGENTS OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
