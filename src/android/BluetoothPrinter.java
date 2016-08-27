package com.ru.cordova.printer.bluetooth;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Hashtable;
import java.util.Set;
import java.util.UUID;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Bitmap.Config;
import android.util.Xml.Encoding;
import android.util.Base64;

public class BluetoothPrinter extends CordovaPlugin {
	private static final String LOG_TAG = "BluetoothPrinter";
	BluetoothAdapter mBluetoothAdapter;
	BluetoothSocket mmSocket;
	BluetoothDevice mmDevice;
	OutputStream mmOutputStream;
	InputStream mmInputStream;
	Thread workerThread;
	byte[] readBuffer;
	int readBufferPosition;
	int counter;
	volatile boolean stopWorker;

	Bitmap bitmap;

	public BluetoothPrinter() {}

	@Override
	public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
		if (action.equals("list")) {
			listBT(callbackContext);
			return true;
		} else if (action.equals("connect")) {
			String name = args.getString(0);
			if (findBT(callbackContext, name)) {
				try {
					connectBT(callbackContext);
				} catch (IOException e) {
					Log.e(LOG_TAG, e.getMessage());
					e.printStackTrace();
				}
			} else {
				callbackContext.error("Bluetooth Device Not Found: " + name);
			}
			return true;
		} else if (action.equals("disconnect")) {
            try {
                disconnectBT(callbackContext);
            } catch (IOException e) {
                Log.e(LOG_TAG, e.getMessage());
                e.printStackTrace();
            }
            return true;
        }
        else if (action.equals("print")) {
			try {
				String msg = args.getString(0);
				print(callbackContext, msg);
			} catch (IOException e) {
				Log.e(LOG_TAG, e.getMessage());
				e.printStackTrace();
			}
			return true;
		}
        else if (action.equals("printPOSCommand")) {
			try {
				String msg = args.getString(0);
                printPOSCommand(callbackContext, hexStringToBytes(msg));
			} catch (IOException e) {
				Log.e(LOG_TAG, e.getMessage());
				e.printStackTrace();
			}
			return true;
		}
		return false;
	}

    //This will return the array list of paired bluetooth printers
	void listBT(CallbackContext callbackContext) {
		BluetoothAdapter mBluetoothAdapter = null;
		String errMsg = null;
		try {
			mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
			if (mBluetoothAdapter == null) {
				errMsg = "No bluetooth adapter available";
				Log.e(LOG_TAG, errMsg);
				callbackContext.error(errMsg);
				return;
			}
			if (!mBluetoothAdapter.isEnabled()) {
				Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
				this.cordova.getActivity().startActivityForResult(enableBluetooth, 0);
			}
			Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
			if (pairedDevices.size() > 0) {
				JSONArray json = new JSONArray();
				for (BluetoothDevice device : pairedDevices) {
					/*
					Hashtable map = new Hashtable();
					map.put("type", device.getType());
					map.put("address", device.getAddress());
					map.put("name", device.getName());
					JSONObject jObj = new JSONObject(map);
					*/
					json.put(device.getName());
				}
				callbackContext.success(json);
			} else {
				callbackContext.error("No Bluetooth Device Found");
			}
			//Log.d(LOG_TAG, "Bluetooth Device Found: " + mmDevice.getName());
		} catch (Exception e) {
			errMsg = e.getMessage();
			Log.e(LOG_TAG, errMsg);
			e.printStackTrace();
			callbackContext.error(errMsg);
		}
	}

	// This will find a bluetooth printer device
	boolean findBT(CallbackContext callbackContext, String name) {
		try {
			mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
			if (mBluetoothAdapter == null) {
				Log.e(LOG_TAG, "No bluetooth adapter available");
			}
			if (!mBluetoothAdapter.isEnabled()) {
				Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
				this.cordova.getActivity().startActivityForResult(enableBluetooth, 0);
			}
			Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
			if (pairedDevices.size() > 0) {
				for (BluetoothDevice device : pairedDevices) {
					if (device.getName().equalsIgnoreCase(name)) {
						mmDevice = device;
						return true;
					}
				}
			}
			Log.d(LOG_TAG, "Bluetooth Device Found: " + mmDevice.getName());
		} catch (Exception e) {
			String errMsg = e.getMessage();
			Log.e(LOG_TAG, errMsg);
			e.printStackTrace();
			callbackContext.error(errMsg);
		}
		return false;
	}

	// Tries to open a connection to the bluetooth printer device
	boolean connectBT(CallbackContext callbackContext) throws IOException {
		try {
			// Standard SerialPortService ID
			UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
			mmSocket = mmDevice.createRfcommSocketToServiceRecord(uuid);
			mmSocket.connect();
			mmOutputStream = mmSocket.getOutputStream();
			mmInputStream = mmSocket.getInputStream();
			beginListenForData();
			//Log.d(LOG_TAG, "Bluetooth Opened: " + mmDevice.getName());
			callbackContext.success("Bluetooth Opened: " + mmDevice.getName());
			return true;
		} catch (Exception e) {
			String errMsg = e.getMessage();
			Log.e(LOG_TAG, errMsg);
			e.printStackTrace();
			callbackContext.error(errMsg);
		}
		return false;
	}

	// After opening a connection to bluetooth printer device,
	// we have to listen and check if a data were sent to be printed.
	void beginListenForData() {
		try {
			final Handler handler = new Handler();
			// This is the ASCII code for a newline character
			final byte delimiter = 10;
			stopWorker = false;
			readBufferPosition = 0;
			readBuffer = new byte[1024];
			workerThread = new Thread(new Runnable() {
				public void run() {
					while (!Thread.currentThread().isInterrupted() && !stopWorker) {
						try {
							int bytesAvailable = mmInputStream.available();
							if (bytesAvailable > 0) {
								byte[] packetBytes = new byte[bytesAvailable];
								mmInputStream.read(packetBytes);
								for (int i = 0; i < bytesAvailable; i++) {
									byte b = packetBytes[i];
									if (b == delimiter) {
										byte[] encodedBytes = new byte[readBufferPosition];
										System.arraycopy(readBuffer, 0, encodedBytes, 0, encodedBytes.length);
										/*
										final String data = new String(encodedBytes, "US-ASCII");
										readBufferPosition = 0;
										handler.post(new Runnable() {
											public void run() {
												myLabel.setText(data);
											}
										});
                                        */
									} else {
										readBuffer[readBufferPosition++] = b;
									}
								}
							}
						} catch (IOException ex) {
							stopWorker = true;
						}
					}
				}
			});
			workerThread.start();
		} catch (NullPointerException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	//This will send data to bluetooth printer
	boolean print(CallbackContext callbackContext, String msg) throws IOException {
		try {

			mmOutputStream.write(msg.getBytes());

			// tell the user data were sent
			//Log.d(LOG_TAG, "Data Sent");
			callbackContext.success("Data Sent");
			return true;

		} catch (Exception e) {
			String errMsg = e.getMessage();
			Log.e(LOG_TAG, errMsg);
			e.printStackTrace();
			callbackContext.error(errMsg);
		}
		return false;
	}

	//This will send data to bluetooth printer
    boolean printImage(CallbackContext callbackContext, String msg) throws IOException {
        try {

            //final String encodedString = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAlgAAADSCAYAAACW0r5LAAAgAElEQVR4Xu2dCdAU1fW3L4giopSIorhEMSoKEYIKuKOiSEoBNwLIarG5IIokxiVAABeicUFAWUtlV5FNiQEhKoKK0VJwIZQbJK4ouO8IX/1u/v1+l6F7unumh5np9+kqSmFu3773Od1zf3POuaerbN26davhgAAEIAABCEAAAhBIjEAVBFZiLOkIAhCAAAQgAAEIWAIILG4ECEAAAhCAAAQgkDABBFbCQOkOAhCAAAQgAAEIILC4ByAAAQhAAAIQgEDCBBBYCQOlOwhAAAIQgAAEIIDA4h6AAAQgAAEIQAACCRNAYCUMlO4gAAEIQAACEIAAAot7AAKVnMDatWtNx44dzapVq8wee+xh5s6da1q1ahWZyueff2569eplz9MxfPhwM3jw4G3OHzFihBkyZIhvn8cff7ypU6eOadGihTn//PNNw4YNTdWqVSNf36/hL7/8Yscwe/Zs+6dx48a2mTfXk046ydxxxx1m1113zfk6W7ZsMW+++aaZNWuW+eqrr8xf//pXU6NGjZz740QIQCBdBBBY6bIns4FAbAKuwNLJAwYMMLfddpupXr16pL6WLl1qhdHXX3+dk8DKvMjll19uJMj22muvSNf3a/Tll1+a3r17my+++MLMmDHD7LPPPrbZsmXLTMuWLc2dd95pBg4cmFP/GzZsMPPmzTPTpk0zzz77rO2jX79+5q677kJg5USUkyCQTgIIrHTalVlBIDKBTIHVpEkT89BDD5kGDRqE9uF5im699daKttk8WPKMjRs3ztSuXbui/ffff29ef/11M2XKFDNz5kz77xJZEnk1a9YMHYNfg7ffftt06tTJesVcT9X48ePNpZdeahYvXmzOOuusyH1rns8884yZNGmSefzxxyvEpNcBAisyShpCoNIQQGBVGlMzUQj4E8gUWGolIaGwX9jx3nvvmc6dO5uVK1dGElhnn322mT59ug0JZh6bN2+2Yui6666zoUqJrXPOOSdsCL6fv/DCC6Z169bm+uuvt390/PTTT7bvJUuWmIcfftgceeSRkfuWCJTHSwJNYzv33HNNz549jbx3EoIIrMgoaQiBSkMAgVVpTM1EIZBdYCmn6KijjrLiQyG/yZMnb+Np8jtbbRSKU9hN3qa///3vWXOwsgks9f/RRx+Z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AQhAAAIQgAAEEFjcAxCAAAQgAAEIQCBhAgishIHSHQQgAAEIQAACEEBgcQ9AAAIQgAAEIACBhAkgsBIGSncQgAAEIAABCEAAgcU9AAEIQAACEIAABBImgMBKGCjdQQACEIAABCAAAQQW9wAEIAABCEAAAhBImAACK2GgdAcBCEAAAhCAAAQQWNwDlYLAli1bzJdffmn03+rVq5vdd9+9bOe9detW89VXX5nNmzebatWqmVq1apkqVaqU7XwYOAQgAIE0EkBgpdGqzMkSkJh69dVXzZgxY8z8+fPNpk2bKsg0aNDA9O3b1/To0cPUqVMnMrGXX37ZtG3b1px99tm235o1a0Y+N5+GElXvvfeeveacOXPM+vXrK7o7+OCDzcUXX2wuvfRS86tf/SrSZdTfzTffbAYPHmzOP/98M3nyZFO7du3Qc0eMGGGGDBni2+7444+3LFu0aGH7bNiwoalatep2bVesWGFOPvnk7f5dNtFcjj32WHPOOefYfiQg4xy5zOv77783AwcONOPHj9/uUnvssYf57W9/a+fVsmVLc95559kxxhW0HjfdN9OnTw+85ySax40bZ2644QY7lltuucXaVRzccfbr18/cddddpkaNGlnxrF271nTs2NGsWrXKTJs2zXTp0iUUp3vOo48+ai644ILQc1ybTpgwwfTu3TuUUVQmoRenAQRKlAACq0QNw7DyI6DF6I477jC33Xab+frrrwM706KuBU2LZ9iiqcVbi9qgQYPMpEmTTK9evfIbZMSztehqUb7mmmu2EYmZp++1115m9OjRplOnTr7Cxm3/0Ucfme7du5slS5aYevXqmXnz5pnmzZuHjiibwMo8+fLLLzdqr3G5R5DAyjy/WbNm5u677zYnnHBCqG28c3OZVzaBlTkmCa5rr73W3gNh4sY9N4qY0P01c+ZMK6h0z44cOdJexxOZO0pgSWxLIOkYMGCAfYbk9c12uDZt0qSJncdRRx2V9ZwoTEJvSBpAoIQJILBK2DgMLTcCWqjuuecec/XVVxstiPq1f9lll5lDDjnECo+ffvrJvPDCC+b22283jz/+uPVIzJ492xx33HFZL+gt3lr8tIDUr18/twHGPEvet27dutlFV6LoD3/4g128tPBKfK1Zs8b87W9/M1OmTLHznTp1qmnfvn3Wq8gLduGFF1a0kSdr6NChZqeddoq0KLZq1coKU9frJQHw+uuv23GIjw6JLC3QrqfPXYyfeOIJIyGlQx7Hd955xyxbtsx6W1577TUrzsaOHWu9MGECWH3kMi9XuFxyySVWFO66664VHBSOledSonrRokX23zPFT5hJo4iJZ555xnpU5Z3047YjBNbnn39ufzjMnTvXTkn32SOPPGIaNWoUWWCpYZ8+feyPkWwe3ihMwrjyOQRKmQACq5Stw9hyIqBQWufOnc3KlSvtYnndddf5hpo++eQTK74kYHr27Bka8nvyySdN69atzfXXX2/7DRMjOQ0+4yR3wcu2aH377bfWs3LvvfeaM88804oceab8jh9//NG2lQhVKE9iU548ecn233//SAIrW6hLok/eQ3GX4JPYUsjPO1yBtXz5cnPSSSdtd02JSYXHJGQkgB988EHrZcx25DqvqMLFZawxyeun8GGUI0xMuOJKYlr89tlnn226jjpO96S4IcIXX3zRhkHr1q1r9t13X7N48WIrpPWcZDsyvZKyu87TcxgkjMOYROFKGwiUMgEEVilbh7HlRMATQhIYjz32mM3pCTokKrp27WoU1njooYes0MgmSu6//377614enB1xrF692lx00UXmrbfeskKwXbt2gZfVIve73/3Ofi7PkJ9w0WdvvPGG6dChg22nfJlRo0ZZD16UfJuoi6IbqssUpFEElsYmQaPcqIkTJ9rxKkcqW55YrvOKI1y8a8hrGEV4eMbKxk19yXOmHwTyPGqeEjeZR5xxeufGEVi//PKLGTZsmP3xII+mxKO8nFFy9Fyb6rmT/ZVDp+clKFQY9V7aEc8Z14BAIQggsApBlT6LSkBCSXlIhx9+uBUOjRs3DhyPQj9uQnGQGPMW1iOPPDJrQvinn35qP3/ggQeMFjcdTZs2Nb///e9t6CXTKxEGSt4lec3k0ZE34ayzzgo85d1337XetS+++MKGmILChFrAlefjee0UjnP/nkRYJ5sYiCqwNFHPo/LNN9+ECttc5xVHuGzcuNEmiitUOHz4cCtEohxBYkLhwCuuuMIsXLgwVJDEGWcuAuvDDz+0c9MzoR8REkbu37P9qHBtet9999n7X4Kxf//+NkTsl6+GwIpy59CmnAkgsMrZeozdl4C300+/oqPuaApD6SX+3nnnndarknko70thHgkVT1hltpGXTIuPkrajHlqAtUPwueees0JQHoa4O+vc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";
            final String encodedString = msg;
            final String pureBase64Encoded = encodedString.substring(encodedString.indexOf(",")  + 1);

            final byte[] decodedBytes = Base64.decode(pureBase64Encoded, Base64.DEFAULT);

            Bitmap decodedBitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);

            bitmap = decodedBitmap;

            int mWidth = bitmap.getWidth();
            int mHeight = bitmap.getHeight();
            //bitmap=resizeImage(bitmap, imageWidth * 8, mHeight);
            bitmap=resizeImage(bitmap, 48 * 8, mHeight);


            byte[]  bt =getBitmapData(bitmap);

            bitmap.recycle();

            mmOutputStream.write(bt);

            // tell the user data were sent
            //Log.d(LOG_TAG, "Data Sent");
            callbackContext.success("Data Sent");
            return true;


        } catch (Exception e) {
            String errMsg = e.getMessage();
            Log.e(LOG_TAG, errMsg);
            e.printStackTrace();
            callbackContext.error(errMsg);
        }
        return false;
    }


    boolean printPOSCommand(CallbackContext callbackContext, byte[] buffer) throws IOException {
        try {
            mmOutputStream.write(buffer);
            // tell the user data were sent
			Log.d(LOG_TAG, "Data Sent");
            callbackContext.success("Data Sent");
            return true;
        } catch (Exception e) {
            String errMsg = e.getMessage();
            Log.e(LOG_TAG, errMsg);
            e.printStackTrace();
            callbackContext.error(errMsg);
        }
        return false;
    }

	// disconnect bluetooth printer.
	boolean disconnectBT(CallbackContext callbackContext) throws IOException {
		try {
			stopWorker = true;
			mmOutputStream.close();
			mmInputStream.close();
			mmSocket.close();
			callbackContext.success("Bluetooth Disconnect");
			return true;
		} catch (Exception e) {
			String errMsg = e.getMessage();
			Log.e(LOG_TAG, errMsg);
			e.printStackTrace();
			callbackContext.error(errMsg);
		}
		return false;
	}


	public byte[] getText(String textStr) {
        // TODO Auto-generated method stubbyte[] send;
        byte[] send=null;
        try {
            send = textStr.getBytes("GBK");
        } catch (UnsupportedEncodingException e) {
            send = textStr.getBytes();
        }
        return send;
    }

    public static byte[] hexStringToBytes(String hexString) {
        hexString = hexString.toLowerCase();
        String[] hexStrings = hexString.split(" ");
        byte[] bytes = new byte[hexStrings.length];
        for (int i = 0; i < hexStrings.length; i++) {
            char[] hexChars = hexStrings[i].toCharArray();
            bytes[i] = (byte) (charToByte(hexChars[0]) << 4 | charToByte(hexChars[1]));
        }
        return bytes;
    }

    private static byte charToByte(char c) {
		return (byte) "0123456789abcdef".indexOf(c);
	}

















	public byte[] getImage(Bitmap bitmap) {
        // TODO Auto-generated method stub
        int mWidth = bitmap.getWidth();
        int mHeight = bitmap.getHeight();
        bitmap=resizeImage(bitmap, 48 * 8, mHeight);
        //bitmap=resizeImage(bitmap, imageWidth * 8, mHeight);
        /*
        mWidth = bitmap.getWidth();
        mHeight = bitmap.getHeight();
        int[] mIntArray = new int[mWidth * mHeight];
        bitmap.getPixels(mIntArray, 0, mWidth, 0, 0, mWidth, mHeight);
        byte[]  bt =getBitmapData(mIntArray, mWidth, mHeight);*/

        byte[]  bt =getBitmapData(bitmap);


        /*try {//?????????????????
            createFile("/sdcard/demo.txt",bt);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }*/


        ////byte[]  bt =StartBmpToPrintCode(bitmap);

        bitmap.recycle();
        return bt;
    }

    private static Bitmap resizeImage(Bitmap bitmap, int w, int h) {
        Bitmap BitmapOrg = bitmap;
        int width = BitmapOrg.getWidth();
        int height = BitmapOrg.getHeight();

        if(width>w)
        {
            float scaleWidth = ((float) w) / width;
            float scaleHeight = ((float) h) / height+24;
            Matrix matrix = new Matrix();
            matrix.postScale(scaleWidth, scaleWidth);
            Bitmap resizedBitmap = Bitmap.createBitmap(BitmapOrg, 0, 0, width,
                    height, matrix, true);
            return resizedBitmap;
        }else{
            Bitmap resizedBitmap = Bitmap.createBitmap(w, height+24, Config.RGB_565);
            Canvas canvas = new Canvas(resizedBitmap);
            Paint paint = new Paint();
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(bitmap, (w-width)/2, 0, paint);
            return resizedBitmap;
        }
    }

    public static byte[] getBitmapData(Bitmap bitmap) {
		byte temp = 0;
		int j = 7;
		int start = 0;
		if (bitmap != null) {
			int mWidth = bitmap.getWidth();
			int mHeight = bitmap.getHeight();

			int[] mIntArray = new int[mWidth * mHeight];
			bitmap.getPixels(mIntArray, 0, mWidth, 0, 0, mWidth, mHeight);
			bitmap.recycle();
			byte []data=encodeYUV420SP(mIntArray, mWidth, mHeight);
			byte[] result = new byte[mWidth * mHeight / 8];
			for (int i = 0; i < mWidth * mHeight; i++) {
				temp = (byte) ((byte) (data[i] << j) + temp);
				j--;
				if (j < 0) {
					j = 7;
				}
				if (i % 8 == 7) {
					result[start++] = temp;
					temp = 0;
				}
			}
			if (j != 7) {
				result[start++] = temp;
			}

			int aHeight = 24 - mHeight % 24;
			int perline = mWidth / 8;
			byte[] add = new byte[aHeight * perline];
			byte[] nresult = new byte[mWidth * mHeight / 8 + aHeight * perline];
			System.arraycopy(result, 0, nresult, 0, result.length);
			System.arraycopy(add, 0, nresult, result.length, add.length);

			byte[] byteContent = new byte[(mWidth / 8 + 4)
					* (mHeight + aHeight)];// ???????
			byte[] bytehead = new byte[4];// ÿ?????
			bytehead[0] = (byte) 0x1f;
			bytehead[1] = (byte) 0x10;
			bytehead[2] = (byte) (mWidth / 8);
			bytehead[3] = (byte) 0x00;
			for (int index = 0; index < mHeight + aHeight; index++) {
				System.arraycopy(bytehead, 0, byteContent, index
						* (perline + 4), 4);
				System.arraycopy(nresult, index * perline, byteContent, index
						* (perline + 4) + 4, perline);
			}
			return byteContent;
		}
		return null;

	}

	public static byte[] encodeYUV420SP(int[] rgba, int width, int height) {
		final int frameSize = width * height;
		byte[] yuv420sp=new byte[frameSize];
		int[] U, V;
		U = new int[frameSize];
		V = new int[frameSize];
		final int uvwidth = width / 2;
		int r, g, b, y, u, v;
		int bits = 8;
		int index = 0;
		int f = 0;
		for (int j = 0; j < height; j++) {
			for (int i = 0; i < width; i++) {
				r = (rgba[index] & 0xff000000) >> 24;
				g = (rgba[index] & 0xff0000) >> 16;
				b = (rgba[index] & 0xff00) >> 8;
				// rgb to yuv
				y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
				u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
				v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
				// clip y
				// yuv420sp[index++] = (byte) ((y < 0) ? 0 : ((y > 255) ? 255 :
				// y));
				byte temp = (byte) ((y < 0) ? 0 : ((y > 255) ? 255 : y));
				yuv420sp[index++] = temp > 0 ? (byte) 1 : (byte) 0;

				// {
				// if (f == 0) {
				// yuv420sp[index++] = 0;
				// f = 1;
				// } else {
				// yuv420sp[index++] = 1;
				// f = 0;
				// }

				// }

			}

		}
		f = 0;
		return yuv420sp;
	}


}
