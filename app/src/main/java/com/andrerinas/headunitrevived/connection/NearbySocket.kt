package com.andrerinas.headunitrevived.connection

import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket

class NearbySocket : Socket() {
    @Volatile var inputStreamWrapper: InputStream? = null
    @Volatile var outputStreamWrapper: OutputStream? = null

    override fun isConnected(): Boolean {
        return true
    }
    
    override fun getInetAddress(): InetAddress {
        return InetAddress.getLoopbackAddress()
    }

    override fun getInputStream(): InputStream {
        return object : InputStream() {
            override fun read(): Int {
                val stream = inputStreamWrapper
                while (stream == null) {
                    Thread.sleep(10)
                }
                return stream.read()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val stream = inputStreamWrapper
                while (stream == null) {
                    Thread.sleep(10)
                }
                return stream.read(b, off, len)
            }
            
            override fun available(): Int {
                return inputStreamWrapper?.available() ?: 0
            }
        }
    }

    override fun getOutputStream(): OutputStream {
        return object : OutputStream() {
            override fun write(b: Int) {
                val stream = outputStreamWrapper
                while (stream == null) {
                    Thread.sleep(10)
                }
                stream.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                val stream = outputStreamWrapper
                while (stream == null) {
                    Thread.sleep(10)
                }
                stream.write(b, off, len)
            }

            override fun flush() {
                outputStreamWrapper?.flush()
            }
        }
    }
}