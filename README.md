# WipeCamera
Android 11 ? から前面カメラと背面カメラを同時に利用できるようになったらしいのでワイプ付きのカメラを作った。  

![Screenshot_20230226-234126](https://user-images.githubusercontent.com/32033405/221417706-a958033d-fd7a-4fcc-b4a4-52bcdd6d7fed.jpg)

# 仕組み
OpenGL を利用してアウトカメラとフロントカメラを描画しています。  
（SurfaceView を2つ利用しているわけではなく、SurfaceView + OpenGL でやっています。録画機能もありますが私の実装が雑なので一回だけ使えます。）

![figma](https://user-images.githubusercontent.com/32033405/221419726-ca7bc872-1581-4b96-9c6c-ac9f1013cbfa.png)

カメラの映像は`SurfaceTexture`を利用することで、フラグメントシェーダからテクスチャとして利用できます。  
`SurfaceTexture#setOnFrameAvailableListener`がカメラのフレームが取得されるたびに呼び出されるので、`SurfaceTexture#updateTexImage`を呼び出してテクスチャを転送？した後、  
`GLES20.glDrawArrays`を呼んで描画するようにしています。  
シェーダーはよくわかりませんがなんか動いています。

![figma](https://user-images.githubusercontent.com/32033405/221419912-2eb50cbc-c255-4c5f-96d7-1ee2356159d3.png)
