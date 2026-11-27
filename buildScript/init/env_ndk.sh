#!/bin/bash

if [ -z "$ANDROID_HOME" ]; then
  if [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
  elif [ -d "$HOME/.local/lib/android/sdk" ]; then
    export ANDROID_HOME="$HOME/.local/lib/android/sdk"
  elif [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  fi
fi

# Use the selected Cronet toolchain without falling back to an old NDK.
if [ "${NATIVE_NAIVE:-0}" = 1 ]; then
  _NDK="${NATIVE_NAIVE_NDK:-$ANDROID_HOME/ndk/28.0.13004108}"
  if [ ! -f "$_NDK/source.properties" ]; then
    echo "Error: Native NaiveProxy requires Android NDK r28 (28.0.13004108)."
    exit 1
  fi
  if ! grep -Eq '^Pkg\.Revision[[:space:]]*=[[:space:]]*28\.0\.13004108[[:space:]]*$' "$_NDK/source.properties"; then
    echo "Error: Unexpected NDK revision for Native NaiveProxy."
    exit 1
  fi
  export ANDROID_NDK_HOME="$_NDK"
  export ANDROID_NDK_ROOT="$_NDK"
  export NDK="$_NDK"
else
  _NDK="$ANDROID_HOME/ndk/25.0.8775105"
  [ -f "$_NDK/source.properties" ] || _NDK="$ANDROID_NDK_HOME"
  [ -f "$_NDK/source.properties" ] || _NDK="$NDK"
  [ -f "$_NDK/source.properties" ] || _NDK="$ANDROID_HOME/ndk-bundle"
  if [ ! -f "$_NDK/source.properties" ]; then
    echo "Error: NDK not found."
    exit 1
  fi
  export ANDROID_NDK_HOME=$_NDK
  export NDK=$_NDK
fi
