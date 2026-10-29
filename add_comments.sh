#!/bin/bash

# Add comment in FileOps.java deleteTree
sed -i 's/String\[\] kids = f.list();/\/\/ ⚡ Bolt: Use list() instead of listFiles() to minimize GC pressure during recursive deletes\n            String[] kids = f.list();/g' src/com/bloatware/bingblop/FileOps.java
# Add comment in MainActivity.java deleteRecursively
sed -i 's/String\[\] kids = f.list();/\/\/ ⚡ Bolt: Use list() instead of listFiles() to minimize GC pressure during recursive deletes\n        String[] kids = f.list();/' src/com/bloatware/bingblop/MainActivity.java
