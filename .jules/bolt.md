## 2024-03-24 - [Minimize allocations in directory traversal]
**Learning:** In Android Java code, `File.listFiles()` creates a large array of `File` objects which spikes memory usage and triggers GC pressure during deep directory traversals.
**Action:** Prefer `File.list()` to get string arrays of filenames and instantiate `File` objects individually in the loop for deletion or recursive walks.
