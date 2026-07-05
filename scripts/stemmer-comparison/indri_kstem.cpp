// TASK-0013 — Indri Krovetz stemmer oracle.
// Reads one token per line from stdin, writes "token\tstem" per line to stdout, using Indri's own
// C++ Krovetz implementation (indri::parse::KrovetzStemmer::kstem_stemmer). This is the ground-truth
// stemmer we compare Lucindri's Lucene KStemFilter against.
//
// kstem_stem_tobuffer() guards too-long (>= MAX_WORD_LENGTH=25), too-short, and non-alphabetic tokens
// with a lowercase no-op copy, so any input is safe. The returned stem may be LONGER than the input.
//
// Build (installed Indri 5.21; libindri.a carries the symbols, the include tree resolves the header
// deps such as Mutex.hpp):
//   g++ -O2 -std=c++11 -w -I/ssd-8TB/installs/indri-5.21/include indri_kstem.cpp \
//       -L/ssd-8TB/installs/indri-5.21/lib -lindri -lz -lpthread -o indri_kstem
#include "indri/KrovetzStemmer.hpp"

#include <iostream>
#include <string>
#include <vector>

int main() {
  indri::parse::KrovetzStemmer stemmer;
  std::string line;
  while (std::getline(std::cin, line)) {
    if (line.empty()) {
      continue;
    }
    // kstem_stemmer takes a mutable char*; copy into a writable buffer.
    std::vector<char> buf(line.begin(), line.end());
    buf.push_back('\0');
    const char* stem = stemmer.kstem_stemmer(buf.data());
    std::cout << line << '\t' << (stem ? stem : line.c_str()) << '\n';
  }
  return 0;
}
