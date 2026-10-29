#!/bin/bash
sed -i 's/String\[\] kids = f.list(); if (kids != null) for (File k : kids) k.delete();/String\[\] kids = f.list(); if (kids != null) for (String k : kids) new File(f, k).delete();/g' src/com/bloatware/bingblop/MainActivity.java
