#!/bin/bash
sed -i 's/String\[\] kids = f.list();/\/\/ ⚡ Bolt: Use list() instead of listFiles() to minimize GC pressure during recursive deletes\n        String[] kids = f.list();/' src/com/bloatware/bingblop/MainActivity.java
