#!/bin/bash
sed -i 's/File\[\] kids = f.listFiles();/String[] kids = f.list();/g' src/com/bloatware/bingblop/FileOps.java
sed -i 's/if (kids != null) for (File k : kids) deleteTree(k, r, quiet);/if (kids != null) for (String k : kids) deleteTree(new File(f, k), r, quiet);/g' src/com/bloatware/bingblop/FileOps.java
